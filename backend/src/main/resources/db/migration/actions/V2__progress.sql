-- Phase 4 E07 T4 (ADR-0067, design E07 §1.7, §6, §7): the owner's requests for a new due date, and the key of the daily
-- digest (T5). Expand only: new tables, no data. Progress updates, confidence, evidence, the red-amber-green override,
-- the owner's changes, cancelling and reopening use the columns and the timeline of actions/V1.

-- A request for a new due date (design E07 §1.7): the owner asks with a reason; a secretary (SECRETARY_AGREES) or the chair
-- (CHAIR_AGREES) agrees or declines it, once. Until then the action keeps its date. At most one is pending per action; it
-- is withdrawn when the date is changed another way or the action is cancelled (and, from T5, marked as done). The reason
-- and the note are business content, never audited (the audit carries hashes).
CREATE TABLE actions_due_request (
    id            uuid        NOT NULL,
    tenant_id     uuid        NOT NULL,
    action_id     uuid        NOT NULL,
    -- The date the action had when asked, and the one asked for.
    old_date      date        NOT NULL,
    new_date      date        NOT NULL CHECK (new_date <> old_date),
    reason        text        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    state         text        NOT NULL CHECK (state IN ('PENDING', 'AGREED', 'DECLINED', 'WITHDRAWN')),
    requested_by  uuid        NOT NULL,
    requested_at  timestamptz NOT NULL,
    -- Who agreed, declined or withdrew it (null for a withdrawal by the system), when, and the decider's note.
    decided_by    uuid,
    decided_at    timestamptz,
    decision_note text        CHECK (char_length(decision_note) BETWEEN 1 AND 500),
    CHECK ((state = 'PENDING') = (decided_at IS NULL)),
    CHECK (state <> 'PENDING' OR (decided_by IS NULL AND decision_note IS NULL)),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, action_id) REFERENCES actions_item (tenant_id, id)
);
CREATE INDEX actions_due_request_action_idx ON actions_due_request (tenant_id, action_id);
-- One pending request per action (409 PENDING_REQUEST).
CREATE UNIQUE INDEX actions_due_request_pending_uk ON actions_due_request (tenant_id, action_id) WHERE state = 'PENDING';

-- The daily digest's key (design E07 §1.8, §9; written by T5): one email per person and working day, so a run that
-- repeats sends nothing twice.
CREATE TABLE actions_digest (
    tenant_id uuid        NOT NULL,
    user_id   uuid        NOT NULL,
    day       date        NOT NULL,
    sent_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, user_id, day)
);

ALTER TABLE actions_due_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_due_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_due_request FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE actions_digest ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_digest FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_digest FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- A request is decided once (design E07 §6): only a pending one changes, only to a decision, and nothing else of it.
CREATE FUNCTION actions_due_request_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state = 'PENDING' OR NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.action_id <> OLD.action_id OR NEW.old_date <> OLD.old_date OR NEW.new_date <> OLD.new_date
        OR NEW.reason <> OLD.reason OR NEW.requested_by <> OLD.requested_by OR NEW.requested_at <> OLD.requested_at THEN
        RAISE EXCEPTION 'due request %: decided once, and only its decision is written', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION actions_due_request_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER actions_due_request_guard BEFORE UPDATE ON actions_due_request FOR EACH ROW
    EXECUTE FUNCTION actions_due_request_guard();

-- The record is sacred (AGENTS.md §3.3): nothing is deleted or truncated; a request changes only its decision; the
-- digest's key is insert only.
REVOKE UPDATE, DELETE, TRUNCATE ON actions_due_request, actions_digest FROM memo_app;
GRANT UPDATE (state, decided_by, decided_at, decision_note) ON actions_due_request TO memo_app;
