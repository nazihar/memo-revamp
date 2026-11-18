-- Phase 4 E03 T4 (ADR-0061, design E03 §1.5, §1.9, §1.10, §6, §7): what the audience does with a published agenda. The
-- last revision each person looked at ("What changed since you last looked"), the papers they opened, and the consent
-- items they ask to discuss.
-- Expand only: new tables. E04 T1 takes the next free number (meeting/V5), not V4 as its plan first said.

-- The last revision of a meeting's agenda each person has seen: what is newer is "What changed since you last looked".
-- One row per person and meeting, moved forward only (never back to an older revision).
CREATE TABLE meeting_reader_state (
    tenant_id     uuid        NOT NULL,
    meeting_id    uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    last_revision int         NOT NULL CHECK (last_revision >= 1),
    seen_at       timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id, user_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_agenda (tenant_id, meeting_id)
);
-- "My meetings": a person's states across their meetings.
CREATE INDEX meeting_reader_state_user_idx ON meeting_reader_state (tenant_id, user_id, meeting_id);

-- Who opened which paper, first (the "Opened" ticks of the meeting page): one row per person and paper, never changed.
-- The record of every open is the audit log (`meeting.paper.opened`); this is its per-person summary for the page.
CREATE TABLE meeting_paper_open (
    tenant_id       uuid        NOT NULL,
    paper_id        uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    first_opened_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, paper_id, user_id),
    FOREIGN KEY (tenant_id, paper_id) REFERENCES meeting_paper (tenant_id, id)
);
CREATE INDEX meeting_paper_open_user_idx ON meeting_paper_open (tenant_id, user_id, paper_id);

-- "Ask to discuss" (design E03 §1.5): a person in the audience asks for a consent item to be lifted for discussion. One
-- open request per person and item; the requester withdraws it until the meeting starts. Append-only: a withdrawal sets
-- [withdrawn_at] once, and nothing else ever changes (the request stays on record for the minutes, E06).
CREATE TABLE meeting_consent_request (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    meeting_id   uuid        NOT NULL,
    item_id      uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    -- The published revision the person read when asking.
    revision     int         NOT NULL CHECK (revision >= 1),
    requested_at timestamptz NOT NULL,
    withdrawn_at timestamptz,
    CHECK (withdrawn_at IS NULL OR withdrawn_at >= requested_at),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_agenda (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES meeting_item (tenant_id, id)
);
CREATE INDEX meeting_consent_request_meeting_idx ON meeting_consent_request (tenant_id, meeting_id);
CREATE INDEX meeting_consent_request_item_idx ON meeting_consent_request (tenant_id, item_id);
-- One open request per person and item.
CREATE UNIQUE INDEX meeting_consent_request_open_uk ON meeting_consent_request (tenant_id, item_id, user_id)
    WHERE withdrawn_at IS NULL;

-- A withdrawal is the only change: withdrawn_at from null to a time, once; every other column stays as it was.
CREATE FUNCTION meeting_consent_request_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF OLD.withdrawn_at IS NOT NULL OR NEW.withdrawn_at IS NULL
        OR (NEW.id, NEW.tenant_id, NEW.meeting_id, NEW.item_id, NEW.user_id, NEW.revision, NEW.requested_at)
            IS DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.meeting_id, OLD.item_id, OLD.user_id, OLD.revision, OLD.requested_at)
    THEN
        RAISE EXCEPTION 'meeting_consent_request: only a withdrawal, once' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION meeting_consent_request_guard() FROM PUBLIC, memo_app;

CREATE TRIGGER meeting_consent_request_withdraw_once BEFORE UPDATE ON meeting_consent_request
    FOR EACH ROW EXECUTE FUNCTION meeting_consent_request_guard();

ALTER TABLE meeting_reader_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_reader_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_reader_state FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_paper_open ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_paper_open FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_paper_open FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_consent_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_consent_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_consent_request FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Nothing is deleted (design E03 §6); a request changes only by its withdrawal.
REVOKE DELETE, TRUNCATE ON meeting_reader_state FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_paper_open FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_consent_request FROM memo_app;
GRANT UPDATE (withdrawn_at) ON meeting_consent_request TO memo_app;
