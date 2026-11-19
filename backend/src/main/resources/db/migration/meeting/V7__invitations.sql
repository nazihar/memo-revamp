-- Phase 4 E05 T2 (ADR-0063, design E05 §1.1, §1.3, §6, §7): meeting invitations by email with iCalendar files. One
-- invitation per meeting (its random UID and sequence), the people it was ever sent to with what they hold, and an
-- append-only log of every message queued. Numbered V7: E04 T1 holds meeting/V5 and E03 T5 meeting/V6 (the E05 plan's V5).
-- Expand only: new tables, one new index on meeting, and invitations for the meetings already scheduled (sent nothing).

-- The invitation of a meeting, created when it is first scheduled. [sequence] is the version of the meeting the next
-- messages describe; it goes up once per batch of changes (three edits before the next send are one update), so it is
-- raised only when the current one was sent ([sent_sequence] = [sequence]). [pending_changes] lists what changed since the
-- last send, in words for the update ("The place changed"). [due_at]: a send is pending from then (debounced).
CREATE TABLE meeting_invitation (
    tenant_id       uuid        NOT NULL,
    meeting_id      uuid        NOT NULL,
    -- RFC 7986 §5.3: a random UUID, never shown in the app, never changed.
    ical_uid        uuid        NOT NULL,
    sequence        int         NOT NULL DEFAULT 0 CHECK (sequence >= 0),
    sent_sequence   int         CHECK (sent_sequence >= 0 AND sent_sequence <= sequence),
    pending_changes text[]      NOT NULL DEFAULT '{}'
        CHECK (pending_changes <@ ARRAY['TIME', 'PLACE', 'LINK', 'TITLE']::text[]),
    cancelled_at    timestamptz,
    due_at          timestamptz,
    reconciled_at   timestamptz,
    created_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, meeting_id),
    UNIQUE (tenant_id, ical_uid),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id)
);
-- The sweep's safety net (design E05 §9 (h)): invitations whose send is overdue.
CREATE INDEX meeting_invitation_due_idx ON meeting_invitation (tenant_id, due_at) WHERE due_at IS NOT NULL;

-- Each person the invitation was ever sent to, and what they hold: [state] INVITED (the event is in their calendar),
-- UNINVITED (they left the audience) or CANCELLED (the meeting was called off); [last_sequence] is the SEQUENCE of the
-- last file they were sent, which never goes down (RFC 5546 §2.1.4), and [held_sequence] the invitation's sequence that
-- file described. [last_outbox_id] is null when the person had invitation emails switched off.
CREATE TABLE meeting_invitee (
    tenant_id      uuid        NOT NULL,
    meeting_id     uuid        NOT NULL,
    user_id        uuid        NOT NULL,
    state          text        NOT NULL CHECK (state IN ('INVITED', 'UNINVITED', 'CANCELLED')),
    last_sequence  int         NOT NULL CHECK (last_sequence >= 0),
    held_sequence  int         NOT NULL CHECK (held_sequence >= 0),
    last_method    text        NOT NULL CHECK (last_method IN ('REQUEST', 'CANCEL')),
    last_kind      text        NOT NULL CHECK (last_kind IN ('INVITATION', 'UPDATE', 'RESEND', 'UNINVITE', 'CANCEL')),
    last_outbox_id uuid,
    first_sent_at  timestamptz NOT NULL,
    last_sent_at   timestamptz NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    CHECK (last_sent_at >= first_sent_at),
    CHECK ((last_method = 'CANCEL') = (last_kind IN ('UNINVITE', 'CANCEL'))),
    PRIMARY KEY (tenant_id, meeting_id, user_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_invitation (tenant_id, meeting_id)
);

-- Every message queued (append-only): to whom, with which SEQUENCE, method and kind, what changed for an update, and
-- the outbox row and in-app item it became. [queued_by] is the secretary for a resend, null for the reconciler.
CREATE TABLE meeting_invitation_message (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    meeting_id      uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    sequence        int         NOT NULL CHECK (sequence >= 0),
    method          text        NOT NULL CHECK (method IN ('REQUEST', 'CANCEL')),
    kind            text        NOT NULL CHECK (kind IN ('INVITATION', 'UPDATE', 'RESEND', 'UNINVITE', 'CANCEL')),
    changes         text[]      NOT NULL DEFAULT '{}'
        CHECK (changes <@ ARRAY['TIME', 'PLACE', 'LINK', 'TITLE']::text[]),
    outbox_id       uuid,
    notification_id uuid,
    queued_by       uuid,
    queued_at       timestamptz NOT NULL,
    CHECK ((method = 'CANCEL') = (kind IN ('UNINVITE', 'CANCEL'))),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_invitation (tenant_id, meeting_id)
);
CREATE INDEX meeting_invitation_message_idx ON meeting_invitation_message (tenant_id, meeting_id, user_id, queued_at);

-- The sweep's window over upcoming meetings (design E05 §9: reminders and the quorum-at-risk warning, T6).
CREATE INDEX meeting_status_start_idx ON meeting (tenant_id, status, starts_at);

-- The UID and the keys never change, a sequence never goes down, and a cancellation stays.
CREATE FUNCTION meeting_invitation_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF (NEW.tenant_id, NEW.meeting_id, NEW.ical_uid, NEW.created_at)
            IS DISTINCT FROM (OLD.tenant_id, OLD.meeting_id, OLD.ical_uid, OLD.created_at)
        OR NEW.sequence < OLD.sequence
        OR NEW.sent_sequence < OLD.sent_sequence
        OR (OLD.sent_sequence IS NOT NULL AND NEW.sent_sequence IS NULL)
        OR (OLD.cancelled_at IS NOT NULL AND NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at)
    THEN
        RAISE EXCEPTION 'meeting_invitation: the UID, keys and cancellation stay, sequences never go down'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION meeting_invitation_guard() FROM PUBLIC, memo_app;

CREATE TRIGGER meeting_invitation_keep BEFORE UPDATE ON meeting_invitation
    FOR EACH ROW EXECUTE FUNCTION meeting_invitation_guard();

-- A person's copies never go backwards: the keys stay, sequences never go down, and a cancelled meeting stays cancelled.
CREATE FUNCTION meeting_invitee_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF (NEW.tenant_id, NEW.meeting_id, NEW.user_id, NEW.first_sent_at)
            IS DISTINCT FROM (OLD.tenant_id, OLD.meeting_id, OLD.user_id, OLD.first_sent_at)
        OR NEW.last_sequence < OLD.last_sequence
        OR NEW.held_sequence < OLD.held_sequence
        OR OLD.state = 'CANCELLED'
    THEN
        RAISE EXCEPTION 'meeting_invitee: the keys stay, sequences never go down, a cancellation is final'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION meeting_invitee_guard() FROM PUBLIC, memo_app;

CREATE TRIGGER meeting_invitee_keep BEFORE UPDATE ON meeting_invitee
    FOR EACH ROW EXECUTE FUNCTION meeting_invitee_guard();

ALTER TABLE meeting_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_invitation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_invitation FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_invitee ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_invitee FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_invitee FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_invitation_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_invitation_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_invitation_message FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Nothing is deleted (design E05 §6); the message log is never changed.
REVOKE DELETE, TRUNCATE ON meeting_invitation FROM memo_app;
REVOKE DELETE, TRUNCATE ON meeting_invitee FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_invitation_message FROM memo_app;

-- Meetings scheduled before this release get their invitation (for "Add to my calendar"), with nothing due: they are
-- sent at their next change, so a deployment mails nobody. A migration sets no tenant, so FORCE is lifted on both tables
-- for the backfill (the owner role then sees and writes every tenant's rows) and put back before the transaction commits.
ALTER TABLE meeting NO FORCE ROW LEVEL SECURITY;
ALTER TABLE meeting_invitation NO FORCE ROW LEVEL SECURITY;
INSERT INTO meeting_invitation (tenant_id, meeting_id, ical_uid, sequence, created_at)
SELECT tenant_id, id, gen_random_uuid(), 0, now()
FROM meeting
WHERE status IN ('SCHEDULED', 'IN_PROGRESS', 'HELD', 'MINUTES_APPROVED', 'CLOSED');
ALTER TABLE meeting_invitation FORCE ROW LEVEL SECURITY;
ALTER TABLE meeting FORCE ROW LEVEL SECURITY;
