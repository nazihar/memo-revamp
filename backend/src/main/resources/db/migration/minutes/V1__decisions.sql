-- Phase 4 E06 T3 (ADR-0065, design E06 §1.1, §1.2, §6, §7): the minutes module's first migration. The decision per agenda
-- item, its append-only entries (the latest is the decision; a correction is a new entry with a reason), and the
-- secretary's notes per item (append-only versions, never part of the record). Expand only: new tables, no data. Rows of
-- other modules (meetings, items, submissions, memos, quorum snapshots, users) are referred to by id and checked by the
-- services (ADR-0006); inside the module the foreign keys are composite (tenant_id, …).

-- One decision per item that needs one (design E06 §1.1). The current entry is [current_seq]; the release to the memo
-- (T4) sets [released_at] and [released_kind] once; the minutes' approval (T6) sets [final_at] once.
CREATE TABLE minutes_decision (
    id            uuid        NOT NULL,
    tenant_id     uuid        NOT NULL,
    meeting_id    uuid        NOT NULL,
    item_id       uuid        NOT NULL,
    forum_id      uuid        NOT NULL,
    -- SUBMISSION items only: the submission decided and the memo it came from.
    submission_id uuid,
    memo_id       uuid,
    current_seq   int         NOT NULL CHECK (current_seq >= 1),
    released_at   timestamptz,
    released_kind text        CHECK (released_kind IN ('APPROVED', 'APPROVED_WITH_CONDITIONS', 'REJECTED', 'DEFERRED', 'RETURNED',
                                                       'REFERRED', 'NOTED')),
    final_at      timestamptz,
    version       bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((released_at IS NULL) = (released_kind IS NULL)),
    CHECK (memo_id IS NULL OR submission_id IS NOT NULL),
    PRIMARY KEY (tenant_id, id),
    -- One decision per item.
    CONSTRAINT minutes_decision_item_uk UNIQUE (tenant_id, item_id)
);
-- A meeting's decisions (the decisions list, the minutes).
CREATE INDEX minutes_decision_meeting_idx ON minutes_decision (tenant_id, meeting_id);
-- The release (T4) and the memo's side: a submission's decision.
CREATE INDEX minutes_decision_submission_idx ON minutes_decision (tenant_id, submission_id) WHERE submission_id IS NOT NULL;
-- The committee's decisions to be ratified.
CREATE INDEX minutes_decision_forum_idx ON minutes_decision (tenant_id, forum_id);

-- The entries of a decision (design E06 §1.1), never changed: entry 1 is the decision as first recorded, each later one a
-- correction with its reason. Free text (the resolution, conditions, dissent reasons, the correction's reason) is
-- business content, never audited (the audit carries hashes). [took_no_part] is derived from the quorum snapshot
-- (recused and stepped out), never sent by a client.
CREATE TABLE minutes_decision_entry (
    tenant_id             uuid        NOT NULL,
    decision_id           uuid        NOT NULL,
    seq                   int         NOT NULL CHECK (seq >= 1),
    outcome_kind          text        NOT NULL CHECK (outcome_kind IN ('APPROVED', 'APPROVED_WITH_CONDITIONS', 'REJECTED', 'DEFERRED',
                                                                       'RETURNED', 'REFERRED', 'NOTED', 'WITHDRAWN')),
    -- The committee type's label, copied from the version pinned on the meeting.
    outcome_label         text        NOT NULL CHECK (char_length(outcome_label) BETWEEN 1 AND 200),
    -- The kind's `resolution` flag (ADR-0056): a resolution gets a number (T4).
    resolution            boolean     NOT NULL,
    text                  text        CHECK (char_length(text) BETWEEN 1 AND 4000),
    -- 0 to 20 lines of 1 to 1,000 characters.
    conditions            jsonb       NOT NULL DEFAULT '[]'::jsonb
                                      CHECK (jsonb_typeof(conditions) = 'array' AND jsonb_array_length(conditions) <= 20
                                             AND octet_length(conditions::text) <= 90000),
    valid_until           date,
    -- DEFERRED: {meetingId} or {date} or {next: true}.
    defer_to              jsonb       CHECK (defer_to IS NULL OR (jsonb_typeof(defer_to) = 'object' AND octet_length(defer_to::text) <= 256)),
    refer_to_forum_id     uuid,
    refer_reason          text        CHECK (char_length(refer_reason) BETWEEN 1 AND 500),
    vote_mode             text        NOT NULL CHECK (vote_mode IN ('CONSENSUS', 'VOTE')),
    votes_for             int         CHECK (votes_for >= 0),
    votes_against         int         CHECK (votes_against >= 0),
    votes_abstain         int         CHECK (votes_abstain >= 0),
    -- [{userId, reason?}]: named dissent, each reason 0 to 1,000 characters.
    dissent               jsonb       NOT NULL DEFAULT '[]'::jsonb
                                      CHECK (jsonb_typeof(dissent) = 'array' AND octet_length(dissent::text) <= 120000),
    abstentions           uuid[]      NOT NULL DEFAULT '{}',
    took_no_part          uuid[]      NOT NULL DEFAULT '{}',
    -- The quorum snapshot taken with the decision (meeting module, ADR-0064).
    snapshot_id           uuid        NOT NULL,
    -- Decided under E05's override: to be ratified at a quorate meeting.
    ratification_required boolean     NOT NULL,
    ratifies_decision_id  uuid,
    -- The "Minutes of the previous meeting" item's decision approves these minutes (T6).
    approves_minutes_ids  uuid[]      NOT NULL DEFAULT '{}',
    -- When the item was taken.
    decided_at            timestamptz NOT NULL,
    -- One action decided a consent section: shared by its entries.
    consent_batch_id      uuid,
    correction            boolean     NOT NULL,
    reason                text        CHECK (char_length(reason) BETWEEN 1 AND 500),
    recorded_by           uuid        NOT NULL,
    recorded_at           timestamptz NOT NULL,
    CHECK (correction = (seq > 1)),
    CHECK (NOT correction OR reason IS NOT NULL),
    CHECK (vote_mode = 'VOTE' OR (votes_for IS NULL AND votes_against IS NULL AND votes_abstain IS NULL)),
    CHECK ((refer_to_forum_id IS NULL) = (outcome_kind <> 'REFERRED')),
    CHECK (defer_to IS NULL OR outcome_kind = 'DEFERRED'),
    CHECK (ratifies_decision_id IS NULL OR NOT ratification_required),
    CHECK (cardinality(abstentions) <= 100 AND cardinality(took_no_part) <= 200),
    PRIMARY KEY (tenant_id, decision_id, seq),
    FOREIGN KEY (tenant_id, decision_id) REFERENCES minutes_decision (tenant_id, id)
);
-- The decisions a later one ratifies (the "to be ratified" list).
CREATE INDEX minutes_decision_entry_ratifies_idx ON minutes_decision_entry (tenant_id, ratifies_decision_id)
    WHERE ratifies_decision_id IS NOT NULL;

-- The secretary's notes per item (design E06 §1.2): one row per saved version, the latest is the note. Plain text, 0 to
-- 10,000 characters; read by the committee's secretaries and forums.manage not recused from the item, never by members,
-- never in a copy or the record. Frozen at the minutes' approval, never deleted.
CREATE TABLE minutes_item_note (
    tenant_id  uuid        NOT NULL,
    meeting_id uuid        NOT NULL,
    item_id    uuid        NOT NULL,
    seq        int         NOT NULL CHECK (seq >= 1),
    text       text        NOT NULL CHECK (char_length(text) <= 10000),
    saved_by   uuid        NOT NULL,
    saved_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, item_id, seq)
);
CREATE INDEX minutes_item_note_meeting_idx ON minutes_item_note (tenant_id, meeting_id, item_id);

ALTER TABLE minutes_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_decision FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_decision_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_decision_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_decision_entry FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_item_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_item_note FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_item_note FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The second line of defence behind the guarded update (design E06 §6): a decision keeps its item, meeting, committee,
-- submission and memo; its current entry only moves forward; the release and the approval are set once and never cleared
-- or changed; the version moves by one.
CREATE FUNCTION minutes_decision_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.meeting_id <> OLD.meeting_id OR NEW.item_id <> OLD.item_id
        OR NEW.forum_id <> OLD.forum_id OR NEW.submission_id IS DISTINCT FROM OLD.submission_id
        OR NEW.memo_id IS DISTINCT FROM OLD.memo_id THEN
        RAISE EXCEPTION 'decision %: its item, meeting, committee, submission and memo never change', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.current_seq < OLD.current_seq OR NEW.current_seq > OLD.current_seq + 1 THEN
        RAISE EXCEPTION 'decision %: entries are appended one at a time', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF (OLD.released_at IS NOT NULL AND (NEW.released_at IS DISTINCT FROM OLD.released_at
            OR NEW.released_kind IS DISTINCT FROM OLD.released_kind))
        OR (OLD.final_at IS NOT NULL AND NEW.final_at IS DISTINCT FROM OLD.final_at) THEN
        RAISE EXCEPTION 'decision %: a release or an approval is set once', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'decision %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_decision_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_decision_guard BEFORE UPDATE ON minutes_decision FOR EACH ROW EXECUTE FUNCTION minutes_decision_guard();

-- The record is sacred (AGENTS.md §3.3): nothing is deleted; entries and notes are append-only; a decision changes only
-- its current entry, its release, its approval and its version.
REVOKE UPDATE, DELETE, TRUNCATE ON minutes_decision, minutes_decision_entry, minutes_item_note FROM memo_app;
GRANT UPDATE (current_seq, released_at, released_kind, final_at, version) ON minutes_decision TO memo_app;
