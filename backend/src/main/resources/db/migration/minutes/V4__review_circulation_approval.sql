-- Phase 4 E06 T6 (ADR-0066, ADR-0051, design E06 §1.5, §1.6.2–§1.6.4, §6, §7): the chair's review, the circulation for
-- comments, the readers' copies and the chair's authenticated confirmation. Expand only: new columns (nullable, or with a
-- default) on `minutes`, new tables, no data. Old app versions ignore them. Users, items,
-- meetings, decisions and files are referred to by id and checked by the services (ADR-0006).

-- The minutes' review and approval (design E06 §1.5): who reviews (the chair, or the vice-chair when the chair is recused
-- from an item or there is none) and as what; the comment deadline once circulated; the next meeting whose "Minutes of
-- the previous meeting" item they are linked to; the meeting and decision that approved them (AT_NEXT_MEETING); when they
-- went to the chair for confirmation; the confirmation (its approval row) and when. [copies_seq] counts the copy builds
-- asked for (the job's instance id).
ALTER TABLE minutes
    ADD COLUMN reviewer_id           uuid,
    ADD COLUMN reviewer_role         text        CHECK (reviewer_role IN ('CHAIR', 'VICE_CHAIR')),
    ADD COLUMN comments_until        timestamptz,
    ADD COLUMN circulated_at         timestamptz,
    ADD COLUMN linked_meeting_id     uuid,
    ADD COLUMN linked_item_id        uuid,
    ADD COLUMN approving_meeting_id  uuid,
    ADD COLUMN approving_decision_id uuid,
    ADD COLUMN awaiting_since        timestamptz,
    ADD COLUMN confirmed_approval_id uuid,
    ADD COLUMN approved_at           timestamptz,
    ADD COLUMN copies_seq            int         NOT NULL DEFAULT 0 CHECK (copies_seq >= 0),
    ADD CONSTRAINT minutes_reviewer_ck CHECK ((reviewer_id IS NULL) = (reviewer_role IS NULL)),
    ADD CONSTRAINT minutes_linked_ck CHECK ((linked_meeting_id IS NULL) = (linked_item_id IS NULL)),
    ADD CONSTRAINT minutes_approving_ck CHECK ((approving_meeting_id IS NULL) = (approving_decision_id IS NULL)),
    ADD CONSTRAINT minutes_approved_ck CHECK ((state = 'APPROVED') = (approved_at IS NOT NULL AND confirmed_approval_id IS NOT NULL));
-- The hourly sweep: circulated minutes whose comment deadline has passed (design E06 §8, §9 (i)).
CREATE INDEX minutes_deadline_idx ON minutes (tenant_id, state, comments_until);
-- The next meeting's "Minutes of the previous meeting" item finds the minutes linked to it.
CREATE INDEX minutes_linked_idx ON minutes (tenant_id, linked_meeting_id) WHERE linked_meeting_id IS NOT NULL;
-- The committee's minutes, for linking them to its next meeting.
CREATE INDEX minutes_forum_idx ON minutes (tenant_id, forum_id, state);

-- The history gains the end of a circulation (early, with a reason, or when every reader answered) and a new deadline.
ALTER TABLE minutes_event DROP CONSTRAINT minutes_event_kind_check;
ALTER TABLE minutes_event ADD CONSTRAINT minutes_event_kind_check
    CHECK (kind IN ('GENERATED', 'ITEM_REGENERATED', 'ITEM_RESTORED', 'RETURNED_TO_DRAFT', 'SENT_FOR_REVIEW', 'AGREED', 'RETURNED',
                    'CIRCULATED', 'COMMENTS_REQUESTED', 'WITHDRAWN', 'DEADLINE_PASSED', 'DEADLINE_CHANGED', 'CIRCULATION_ENDED',
                    'LINKED_TO_MEETING', 'APPROVED_AT_MEETING', 'CONFIRMED', 'CORRECTION_APPROVED'));

-- A reader's comment on circulated minutes (design E06 §1.5, §1.6.3): "No comments" (once per reader) or a suggestion
-- (1 to 2,000 characters, on an item or on the minutes in general). The secretary's response is set once (ADOPTED or
-- NOT_ADOPTED, with an optional note). Free text is never audited, only its SHA-256.
CREATE TABLE minutes_comment (
    id            uuid        NOT NULL,
    tenant_id     uuid        NOT NULL,
    meeting_id    uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    item_id       uuid,
    kind          text        NOT NULL CHECK (kind IN ('NO_COMMENTS', 'SUGGESTION')),
    text          text        CHECK (char_length(text) BETWEEN 1 AND 2000),
    at            timestamptz NOT NULL,
    response      text        CHECK (response IN ('ADOPTED', 'NOT_ADOPTED')),
    response_note text        CHECK (char_length(response_note) <= 1000),
    responded_by  uuid,
    responded_at  timestamptz,
    CHECK ((kind = 'SUGGESTION') = (text IS NOT NULL)),
    CHECK (kind = 'SUGGESTION' OR (item_id IS NULL AND response IS NULL)),
    CHECK ((response IS NULL) = (responded_by IS NULL) AND (response IS NULL) = (responded_at IS NULL)),
    CHECK (response IS NOT NULL OR response_note IS NULL),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES minutes (tenant_id, meeting_id)
);
-- A reader's own comments, and the secretary's panel (and the foreign key).
CREATE INDEX minutes_comment_meeting_idx ON minutes_comment (tenant_id, meeting_id, user_id);
-- "No comments" once per reader.
CREATE UNIQUE INDEX minutes_comment_none_uk ON minutes_comment (tenant_id, meeting_id, user_id) WHERE kind = 'NO_COMMENTS';

-- Someone asked by a secretary for comments on named items (design E06 §1.5, CGI §6.1's technical review): anyone in the
-- tenant cleared for the meeting; the other items are redacted in their copy. Never changed; asking again adds a row.
CREATE TABLE minutes_comment_request (
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    meeting_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    item_ids   uuid[]      NOT NULL CHECK (cardinality(item_ids) BETWEEN 1 AND 200),
    asked_by   uuid        NOT NULL,
    asked_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES minutes (tenant_id, meeting_id)
);
CREATE INDEX minutes_comment_request_meeting_idx ON minutes_comment_request (tenant_id, meeting_id, user_id);

-- A reader's copy (ADR-0066, design E06 §1.5): one per distinct redaction set and version of the draft, built by the
-- `minutes-copies` job (at most 50 sets per minutes). [redacted] is [{itemId, kind}] with kind RECUSED (the heading, the
-- reader's declaration and the decision) or RESTRICTED (the heading or "Item n — restricted" only); [set_key] is the
-- SHA-256 of its canonical form. The PDF is a file of purpose `minutes.copy` ([file_id], [file_version_id], [sha256]),
-- never served but through the minutes' copy endpoint.
CREATE TABLE minutes_copy (
    id                uuid        NOT NULL,
    tenant_id         uuid        NOT NULL,
    meeting_id        uuid        NOT NULL,
    stage             text        NOT NULL CHECK (stage IN ('DRAFT', 'APPROVED')),
    redacted          jsonb       NOT NULL CHECK (jsonb_typeof(redacted) = 'array' AND octet_length(redacted::text) <= 65536),
    set_key           text        NOT NULL CHECK (set_key ~ '^[0-9a-f]{64}$'),
    source_version_id uuid        NOT NULL,
    state             text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DONE', 'FAILED')),
    error             text        CHECK (char_length(error) BETWEEN 1 AND 100),
    file_id           uuid,
    file_version_id   uuid,
    sha256            text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at        timestamptz NOT NULL,
    built_at          timestamptz,
    version           bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((state = 'DONE') = (file_version_id IS NOT NULL AND sha256 IS NOT NULL AND built_at IS NOT NULL)),
    CHECK ((state = 'FAILED') = (error IS NOT NULL)),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES minutes (tenant_id, meeting_id)
);
CREATE UNIQUE INDEX minutes_copy_uk ON minutes_copy (tenant_id, meeting_id, stage, source_version_id, set_key);
-- A reader's latest built copy for their set.
CREATE INDEX minutes_copy_set_idx ON minutes_copy (tenant_id, meeting_id, set_key, created_at);

-- The chair's authenticated confirmation of the minutes (ADR-0051, ADR-0066, design E06 §1.6.4): who (the chair or the
-- vice-chair, never on their behalf), how they were authenticated (method, second-factor time, step-up age, the session's
-- hash), from where (IP, user agent), what (the .docx version and its SHA-256), and a record hash over all of it chained
-- to the committee's previous minutes approval. Append-only. T8's corrections add their kind.
CREATE TABLE minutes_approval (
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    meeting_id      uuid        NOT NULL,
    forum_id        uuid        NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('CONFIRMATION')),
    actor_id        uuid        NOT NULL,
    role            text        NOT NULL CHECK (role IN ('CHAIR', 'VICE_CHAIR')),
    authentication  jsonb       NOT NULL CHECK (jsonb_typeof(authentication) = 'object' AND octet_length(authentication::text) <= 4096),
    client          jsonb       NOT NULL CHECK (jsonb_typeof(client) = 'object' AND octet_length(client::text) <= 4096),
    docx_version_id uuid        NOT NULL,
    docx_sha256     text        NOT NULL CHECK (docx_sha256 ~ '^[0-9a-f]{64}$'),
    previous_hash   text        CHECK (previous_hash ~ '^[0-9a-f]{64}$'),
    record_hash     text        NOT NULL CHECK (record_hash ~ '^[0-9a-f]{64}$'),
    at              timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES minutes (tenant_id, meeting_id)
);
-- The committee's chain in time order (design E06 §7).
CREATE INDEX minutes_approval_forum_idx ON minutes_approval (tenant_id, forum_id, at);
CREATE INDEX minutes_approval_meeting_idx ON minutes_approval (tenant_id, meeting_id);
-- The chain never forks: one approval follows each (and one starts it).
CREATE UNIQUE INDEX minutes_approval_chain_uk ON minutes_approval (tenant_id, forum_id, previous_hash) NULLS NOT DISTINCT;

-- The sweep's reminders on minutes (design E06 §9 (j)–(l)), each marked once per person, kind and key before it is sent.
CREATE TABLE minutes_reminder (
    tenant_id  uuid        NOT NULL,
    meeting_id uuid        NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('COMMENTS_CLOSING', 'DRAFT_OVERDUE', 'CONFIRMATION')),
    user_id    uuid        NOT NULL,
    key        text        NOT NULL CHECK (char_length(key) BETWEEN 1 AND 100),
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id, kind, user_id, key),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES minutes (tenant_id, meeting_id)
);

ALTER TABLE minutes_comment ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_comment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_comment FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_comment_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_comment_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_comment_request FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_copy ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_copy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_copy FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_approval FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_reminder ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_reminder FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_reminder FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The minutes' guard (V3) gains the approval: once approved, the confirmation and its time never change, and a copy built
-- or linked meeting is not rewritten behind the services' backs (the reviewer and links may change while in review).
CREATE OR REPLACE FUNCTION minutes_guard() RETURNS trigger
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
    IF NEW.work_seq < OLD.work_seq OR NEW.copies_seq < OLD.copies_seq THEN
        RAISE EXCEPTION 'minutes %: requests are only counted up', OLD.meeting_id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state = 'APPROVED' AND (NEW.state <> 'APPROVED' OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
            OR NEW.confirmed_approval_id IS DISTINCT FROM OLD.confirmed_approval_id
            OR NEW.approving_meeting_id IS DISTINCT FROM OLD.approving_meeting_id) THEN
        RAISE EXCEPTION 'minutes %: approved minutes stay approved as they were', OLD.meeting_id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'minutes %: the version moves by one', OLD.meeting_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_guard() FROM PUBLIC, memo_app;

-- A comment's response is set once; nothing else of a comment ever changes.
CREATE FUNCTION minutes_comment_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF OLD.response IS NOT NULL THEN
        RAISE EXCEPTION 'comment %: answered once', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.meeting_id <> OLD.meeting_id OR NEW.user_id <> OLD.user_id
        OR NEW.item_id IS DISTINCT FROM OLD.item_id OR NEW.kind <> OLD.kind OR NEW.text IS DISTINCT FROM OLD.text
        OR NEW.at <> OLD.at THEN
        RAISE EXCEPTION 'comment %: only its response is written', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_comment_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_comment_guard BEFORE UPDATE ON minutes_comment FOR EACH ROW EXECUTE FUNCTION minutes_comment_guard();

-- A copy keeps its minutes, stage, set and source; it moves PENDING → DONE or FAILED (and FAILED → PENDING to try again);
-- a built copy's file never changes; the version moves by one.
CREATE FUNCTION minutes_copy_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.meeting_id <> OLD.meeting_id OR NEW.stage <> OLD.stage
        OR NEW.redacted <> OLD.redacted OR NEW.set_key <> OLD.set_key OR NEW.source_version_id <> OLD.source_version_id
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'copy %: its minutes, set and source never change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state = 'DONE' OR (OLD.state = 'FAILED' AND NEW.state <> 'PENDING') OR (OLD.state = 'PENDING' AND NEW.state = 'PENDING') THEN
        RAISE EXCEPTION 'copy %: % cannot become %', OLD.id, OLD.state, NEW.state USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'copy %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_copy_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_copy_guard BEFORE UPDATE ON minutes_copy FOR EACH ROW EXECUTE FUNCTION minutes_copy_guard();

-- The record is sacred (AGENTS.md §3.3): nothing is deleted; requests, approvals and reminders are append-only; a comment
-- changes only its response, a copy only its build; the minutes gain their review and approval columns.
REVOKE UPDATE, DELETE, TRUNCATE ON minutes_comment, minutes_comment_request, minutes_copy, minutes_approval, minutes_reminder
    FROM memo_app;
GRANT UPDATE (response, response_note, responded_by, responded_at) ON minutes_comment TO memo_app;
GRANT UPDATE (state, error, file_id, file_version_id, sha256, built_at, version) ON minutes_copy TO memo_app;
GRANT UPDATE (reviewer_id, reviewer_role, comments_until, circulated_at, linked_meeting_id, linked_item_id, approving_meeting_id,
              approving_decision_id, awaiting_since, confirmed_approval_id, approved_at, copies_seq)
    ON minutes TO memo_app;
