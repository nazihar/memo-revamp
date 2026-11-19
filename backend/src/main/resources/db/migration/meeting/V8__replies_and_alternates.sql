-- Phase 4 E05 T3 (ADR-0063, ADR-0064, design E05 §1.4, §1.5, §6, §7): replies to meeting invitations ("Will you
-- attend?"), their history, the reminders' idempotency keys (used by T6), and alternates standing in for a member as a
-- new audience source. Expand only: new tables, two new nullable columns, a widened CHECK and new constraints that hold
-- for every existing row (no row is ALTERNATE yet). Old app versions ignore the columns and never write ALTERNATE rows.

-- A person's current answer for a meeting. No row: no answer. [for_starts_at] and [for_ends_at] are the meeting's times
-- when it was given: the answer is stale ("for the old time") when they differ from the meeting's. [how] is IN_APP when
-- the person answered, else how they told the secretary ([recorded_by]) who recorded it. Changed by a guarded update on
-- [version]; every answer is also an event below.
CREATE TABLE meeting_rsvp (
    tenant_id         uuid        NOT NULL,
    meeting_id        uuid        NOT NULL,
    user_id           uuid        NOT NULL,
    answer            text        NOT NULL CHECK (answer IN ('IN_PERSON', 'REMOTE', 'APOLOGIES')),
    alternate_attends boolean     NOT NULL DEFAULT false,
    note              text        CHECK (char_length(note) BETWEEN 1 AND 300),
    for_starts_at     timestamptz NOT NULL,
    for_ends_at       timestamptz NOT NULL,
    recorded_by       uuid        NOT NULL,
    how               text        NOT NULL CHECK (how IN ('IN_APP', 'EMAIL', 'PHONE', 'IN_PERSON', 'OTHER')),
    how_note          text        CHECK (char_length(how_note) BETWEEN 1 AND 200),
    answered_at       timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CHECK (NOT alternate_attends OR answer = 'APOLOGIES'),
    CHECK (how <> 'IN_APP' OR (recorded_by = user_id AND how_note IS NULL)),
    PRIMARY KEY (tenant_id, meeting_id, user_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id)
);
-- "My meetings" with the viewer's answer (design E05 §8): a left join by user.
CREATE INDEX meeting_rsvp_user_idx ON meeting_rsvp (tenant_id, user_id, meeting_id);

-- Every answer given or recorded (append-only): the note is kept as its SHA-256 only (the audit's form), the text lives
-- on the current row.
CREATE TABLE meeting_rsvp_event (
    id                uuid        NOT NULL,
    tenant_id         uuid        NOT NULL,
    meeting_id        uuid        NOT NULL,
    user_id           uuid        NOT NULL,
    answer            text        NOT NULL CHECK (answer IN ('IN_PERSON', 'REMOTE', 'APOLOGIES')),
    alternate_attends boolean     NOT NULL,
    note_sha256       text        CHECK (note_sha256 ~ '^[0-9a-f]{64}$'),
    for_starts_at     timestamptz NOT NULL,
    for_ends_at       timestamptz NOT NULL,
    how               text        NOT NULL CHECK (how IN ('IN_APP', 'EMAIL', 'PHONE', 'IN_PERSON', 'OTHER')),
    recorded_by       uuid        NOT NULL,
    at                timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id, user_id) REFERENCES meeting_rsvp (tenant_id, meeting_id, user_id)
);
CREATE INDEX meeting_rsvp_event_idx ON meeting_rsvp_event (tenant_id, meeting_id, user_id, at);

-- The sweep's reminders sent (T6, design E05 §9): one row per person, kind and key (the deadline day, or the
-- invitation's sequence), inserted with ON CONFLICT DO NOTHING so each is sent once.
CREATE TABLE meeting_reminder (
    tenant_id  uuid        NOT NULL,
    meeting_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('REPLY', 'MEETING', 'QUORUM_AT_RISK')),
    key        text        NOT NULL CHECK (char_length(key) BETWEEN 1 AND 64),
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id, user_id, kind, key),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id)
);

-- Alternates (ADR-0064, design E05 §1.5): a member who sends apologies may ask their seat's alternate to attend. The
-- alternate joins the audience as ALTERNATE for that seat ([for_member_id], the forum membership) and member
-- ([for_user_id]); both are set exactly for ALTERNATE rows. A seat has at most one alternate standing in at a time.
ALTER TABLE meeting_audience DROP CONSTRAINT meeting_audience_source_check;
ALTER TABLE meeting_audience ADD CONSTRAINT meeting_audience_source_check
    CHECK (source IN ('MEMBER', 'GUEST', 'ATTENDEE', 'ALTERNATE'));
ALTER TABLE meeting_audience ADD COLUMN for_member_id uuid;
ALTER TABLE meeting_audience ADD COLUMN for_user_id uuid;
ALTER TABLE meeting_audience ADD CONSTRAINT meeting_audience_alternate_check
    CHECK ((source = 'ALTERNATE') = (for_member_id IS NOT NULL) AND (source = 'ALTERNATE') = (for_user_id IS NOT NULL));
CREATE UNIQUE INDEX meeting_audience_seat_idx ON meeting_audience (tenant_id, meeting_id, for_member_id)
    WHERE source = 'ALTERNATE' AND removed_at IS NULL;
-- The recusal rule's inheritance (design E05 §1.5): who stands in for a declarant at the declaration's meeting.
CREATE INDEX meeting_audience_for_user_idx ON meeting_audience (tenant_id, meeting_id, for_user_id)
    WHERE source = 'ALTERNATE' AND removed_at IS NULL;

-- A reply's keys never change; history never changes.
CREATE FUNCTION meeting_rsvp_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF (NEW.tenant_id, NEW.meeting_id, NEW.user_id) IS DISTINCT FROM (OLD.tenant_id, OLD.meeting_id, OLD.user_id)
        OR NEW.version <= OLD.version
    THEN
        RAISE EXCEPTION 'meeting_rsvp: the keys stay and every change raises the version'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION meeting_rsvp_guard() FROM PUBLIC, memo_app;

CREATE TRIGGER meeting_rsvp_keep BEFORE UPDATE ON meeting_rsvp
    FOR EACH ROW EXECUTE FUNCTION meeting_rsvp_guard();

ALTER TABLE meeting_rsvp ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_rsvp FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_rsvp FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_rsvp_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_rsvp_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_rsvp_event FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_reminder ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_reminder FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_reminder FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Nothing is deleted (design E05 §6); the history and the reminders sent are never changed.
REVOKE DELETE, TRUNCATE ON meeting_rsvp FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_rsvp_event FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_reminder FROM memo_app;
