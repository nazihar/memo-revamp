-- Phase 4 E06 T5 (ADR-0066, design E06 §1.5, §1.6.1, §6, §7): a meeting's minutes, drafted from the committee's template
-- and edited in ONLYOFFICE. One row per meeting, created when the meeting is marked as held, with the committee type's
-- minutes policy copied from the version pinned on the meeting; and the minutes' append-only history. The review,
-- circulation and approval tables (comments, copies, approvals) are T6's, in V4. Expand only: new tables, no data. The
-- meeting, the committee, the draft's file and the users are referred to by id and checked by the services (ADR-0006).

-- The minutes of a meeting (design E06 §1.5). [file_id] is the draft (purpose `minutes.draft`), set by its first version;
-- [work_*] is the draft job asked for last (a whole draft, or one item filled again or restored): PENDING while it runs,
-- FAILED with a code when it gave up; [work_seq] counts the requests (the job's instance id). The structure check of the
-- draft's last checked version is [checked_version_id] with its result: whether every agenda item has exactly one locked
-- control, the problems by item, and the tracked changes and comments left unresolved.
CREATE TABLE minutes (
    tenant_id          uuid        NOT NULL,
    meeting_id         uuid        NOT NULL,
    forum_id           uuid        NOT NULL,
    -- The committee type's `minutes` policy as pinned on the meeting when it was held (ADR-0066): later type changes do
    -- not alter these minutes.
    policy             jsonb       NOT NULL CHECK (jsonb_typeof(policy) = 'object' AND octet_length(policy::text) <= 8192),
    state              text        NOT NULL DEFAULT 'DRAFT'
                                   CHECK (state IN ('DRAFT', 'CHAIR_REVIEW', 'CIRCULATED', 'AWAITING_CHAIR', 'APPROVED')),
    file_id            uuid,
    work_seq           int         NOT NULL DEFAULT 0 CHECK (work_seq >= 0),
    work_kind          text        CHECK (work_kind IN ('ALL', 'ITEM')),
    work_item_id       uuid,
    work_state         text        CHECK (work_state IN ('PENDING', 'FAILED')),
    work_error         text        CHECK (char_length(work_error) BETWEEN 1 AND 100),
    work_requested_by  uuid,
    work_requested_at  timestamptz,
    checked_version_id uuid,
    structure_ok       boolean,
    -- [{itemId?, number?, tag?, problem}]: MISSING, DUPLICATED, NESTED, UNLOCKED or UNKNOWN; no item text.
    structure_problems jsonb       NOT NULL DEFAULT '[]'::jsonb
                                   CHECK (jsonb_typeof(structure_problems) = 'array' AND octet_length(structure_problems::text) <= 65536),
    tracked_changes    int         NOT NULL DEFAULT 0 CHECK (tracked_changes >= 0),
    comments           int         NOT NULL DEFAULT 0 CHECK (comments >= 0),
    checked_at         timestamptz,
    -- When the meeting was marked as held ("the meeting closed at …").
    held_at            timestamptz NOT NULL,
    created_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((work_kind IS NULL) = (work_seq = 0)),
    CHECK (work_kind <> 'ITEM' OR work_item_id IS NOT NULL),
    CHECK ((work_state = 'FAILED') = (work_error IS NOT NULL)),
    CHECK ((checked_version_id IS NULL) = (structure_ok IS NULL)),
    PRIMARY KEY (tenant_id, meeting_id)
);
-- The hourly sweep (T6: circulation deadlines, overdue drafts) reads the minutes by state.
CREATE INDEX minutes_state_idx ON minutes (tenant_id, state);
-- A file version saved by the editor finds its minutes.
CREATE UNIQUE INDEX minutes_file_uk ON minutes (tenant_id, file_id) WHERE file_id IS NOT NULL;

-- What happened to the minutes (design E06 §1.5), never changed: the draft made, an item filled again or restored, the
-- minutes sent back to the draft by a corrected decision; T6 adds the review, circulation and approval steps. [note] is
-- free text (0 to 1,000 characters) and never audited.
CREATE TABLE minutes_event (
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    meeting_id      uuid        NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('GENERATED', 'ITEM_REGENERATED', 'ITEM_RESTORED', 'RETURNED_TO_DRAFT',
                                                         'SENT_FOR_REVIEW', 'AGREED', 'RETURNED', 'CIRCULATED',
                                                         'COMMENTS_REQUESTED', 'WITHDRAWN', 'DEADLINE_PASSED',
                                                         'LINKED_TO_MEETING', 'APPROVED_AT_MEETING', 'CONFIRMED',
                                                         'CORRECTION_APPROVED')),
    item_id         uuid,
    actor           uuid,
    on_behalf_by    uuid,
    how             text        CHECK (char_length(how) BETWEEN 1 AND 40),
    note            text        CHECK (char_length(note) <= 1000),
    file_version_id uuid,
    sha256          text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    at              timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES minutes (tenant_id, meeting_id)
);
-- A meeting's history in time order (and the foreign key).
CREATE INDEX minutes_event_meeting_idx ON minutes_event (tenant_id, meeting_id, at);

ALTER TABLE minutes ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_event FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The second line of defence behind the guarded update (design E06 §6): the minutes keep their meeting, committee, policy
-- and the time the meeting was held; the draft's file is set once and never changes; requests are only counted up; the
-- version moves by one; approved minutes stay approved.
CREATE FUNCTION minutes_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.meeting_id <> OLD.meeting_id OR NEW.forum_id <> OLD.forum_id
        OR NEW.policy <> OLD.policy OR NEW.held_at <> OLD.held_at OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'minutes %: the meeting, committee, policy and times never change', OLD.meeting_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.file_id IS NOT NULL AND NEW.file_id IS DISTINCT FROM OLD.file_id THEN
        RAISE EXCEPTION 'minutes %: the draft''s file is set once', OLD.meeting_id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.work_seq < OLD.work_seq THEN
        RAISE EXCEPTION 'minutes %: requests are only counted up', OLD.meeting_id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state = 'APPROVED' AND NEW.state <> 'APPROVED' THEN
        RAISE EXCEPTION 'minutes %: approved minutes stay approved', OLD.meeting_id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'minutes %: the version moves by one', OLD.meeting_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_guard BEFORE UPDATE ON minutes FOR EACH ROW EXECUTE FUNCTION minutes_guard();

-- The record is sacred (AGENTS.md §3.3): nothing is deleted; the history is append-only; the minutes change only their
-- stage, their draft's file, the draft job asked for, the structure check and their version.
REVOKE UPDATE, DELETE, TRUNCATE ON minutes, minutes_event FROM memo_app;
GRANT UPDATE (state, file_id, work_seq, work_kind, work_item_id, work_state, work_error, work_requested_by, work_requested_at,
              checked_version_id, structure_ok, structure_problems, tracked_changes, comments, checked_at, version)
    ON minutes TO memo_app;
