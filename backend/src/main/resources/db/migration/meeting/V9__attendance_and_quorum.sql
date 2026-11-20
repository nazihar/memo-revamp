-- Phase 4 E05 T4 (ADR-0064, design E05 §1.6, §1.7, §6, §7): the attendance register of a meeting, its append-only
-- events, the item in progress, and the log of the live quorum's changes (reached, lost, restored). The snapshots taken
-- with each decision are T5's (the next free number). Expand only: new tables, no change to existing ones, no data.

-- The register: one current row per person who may attend (a roster member on the meeting's date, an alternate standing
-- in for a seat, a guest, an item attendee), opened when the meeting starts (or at a person's first event). Everything
-- but [capacity] and the seat is derived from the events below, recomputed in each event's transaction; changed only by
-- a guarded update on [version] (the If-Match of the register's writes).
CREATE TABLE meeting_attendance (
    tenant_id           uuid        NOT NULL,
    meeting_id          uuid        NOT NULL,
    user_id             uuid        NOT NULL,
    capacity            text        NOT NULL CHECK (capacity IN ('MEMBER', 'ALTERNATE', 'GUEST', 'ATTENDEE')),
    -- For an alternate: the seat (forum membership) and the member they stand in for.
    for_member_id       uuid,
    for_user_id         uuid,
    state               text        NOT NULL CHECK (state IN ('EXPECTED', 'PRESENT', 'LEFT', 'APOLOGIES', 'ABSENT')),
    mode                text        CHECK (mode IN ('IN_PERSON', 'REMOTE')),
    -- The item the person stepped out of (a recused member leaving the room for it), while present.
    stepped_out_of      uuid,
    first_arrived_at    timestamptz,
    last_left_at        timestamptz,
    arrived_during_item uuid,
    left_during_item    uuid,
    opened_at           timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CHECK ((capacity = 'ALTERNATE') = (for_member_id IS NOT NULL) AND (capacity = 'ALTERNATE') = (for_user_id IS NOT NULL)),
    CHECK ((state = 'PRESENT') = (mode IS NOT NULL)),
    CHECK (stepped_out_of IS NULL OR state = 'PRESENT'),
    PRIMARY KEY (tenant_id, meeting_id, user_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id)
);

-- What was recorded (append-only): apologies, absent, arrived (with the mode), a change of mode, left, stepped out of and
-- back into one item, and VOIDED, which cancels one earlier event with a reason. [at] is when it happened (default: when
-- it was recorded), [item_id] the item in progress then. [source] RSVP marks apologies written from the replies when the
-- meeting started. [correction]: recorded after the meeting was held, with a reason.
CREATE TABLE meeting_attendance_event (
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    meeting_id     uuid        NOT NULL,
    user_id        uuid        NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('APOLOGIES', 'ABSENT', 'ARRIVED', 'MODE_CHANGED', 'LEFT', 'STEPPED_OUT',
                                                       'STEPPED_BACK', 'VOIDED')),
    mode           text        CHECK (mode IN ('IN_PERSON', 'REMOTE')),
    at             timestamptz NOT NULL,
    item_id        uuid,
    voids_event_id uuid,
    reason         text        CHECK (char_length(reason) BETWEEN 1 AND 500),
    note           text        CHECK (char_length(note) BETWEEN 1 AND 300),
    source         text        NOT NULL CHECK (source IN ('RECORDED', 'RSVP')),
    correction     boolean     NOT NULL,
    recorded_by    uuid,
    recorded_at    timestamptz NOT NULL,
    CHECK ((kind IN ('ARRIVED', 'MODE_CHANGED')) = (mode IS NOT NULL)),
    CHECK ((kind = 'VOIDED') = (voids_event_id IS NOT NULL)),
    CHECK (kind NOT IN ('STEPPED_OUT', 'STEPPED_BACK') OR item_id IS NOT NULL),
    CHECK ((kind <> 'VOIDED' AND NOT correction) OR reason IS NOT NULL),
    CHECK (source = 'RECORDED' OR kind = 'APOLOGIES'),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id, user_id) REFERENCES meeting_attendance (tenant_id, meeting_id, user_id),
    FOREIGN KEY (tenant_id, voids_event_id) REFERENCES meeting_attendance_event (tenant_id, id)
);
-- The register and the live quorum read a meeting's events in time order; a person's events re-derive their row.
CREATE INDEX meeting_attendance_event_idx ON meeting_attendance_event (tenant_id, meeting_id, at, id);
CREATE INDEX meeting_attendance_event_user_idx ON meeting_attendance_event (tenant_id, meeting_id, user_id, at);
-- An event is voided at most once.
CREATE UNIQUE INDEX meeting_attendance_event_voids_idx ON meeting_attendance_event (tenant_id, voids_event_id)
    WHERE voids_event_id IS NOT NULL;

-- The item in progress ("Now: item 5"), append-only: the latest row started at or before an instant is the item then.
CREATE TABLE meeting_item_progress (
    id          uuid        NOT NULL,
    tenant_id   uuid        NOT NULL,
    meeting_id  uuid        NOT NULL,
    item_id     uuid        NOT NULL,
    started_at  timestamptz NOT NULL,
    recorded_by uuid,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id)
);
CREATE INDEX meeting_item_progress_idx ON meeting_item_progress (tenant_id, meeting_id, started_at, id);

-- Each flip of the live quorum while the meeting is in progress (append-only): REACHED (quorate for the first time),
-- LOST and RESTORED, with the numbers and unmet conditions then. MEETING: the meeting-level quorum, [item_id] the item
-- in progress then (T4); ITEM: one item's quorum, [item_id] that item (T5). [cause_event_id]: the event that flipped it.
CREATE TABLE meeting_quorum_change (
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    meeting_id     uuid        NOT NULL,
    level          text        NOT NULL CHECK (level IN ('MEETING', 'ITEM')),
    item_id        uuid,
    kind           text        NOT NULL CHECK (kind IN ('REACHED', 'LOST', 'RESTORED')),
    needed         int         NOT NULL CHECK (needed >= 0),
    present        int         NOT NULL CHECK (present >= 0),
    unmet          jsonb       NOT NULL CHECK (jsonb_typeof(unmet) = 'array'),
    at             timestamptz NOT NULL,
    cause_event_id uuid,
    recorded_at    timestamptz NOT NULL,
    CHECK (level = 'MEETING' OR item_id IS NOT NULL),
    CHECK (level = 'ITEM' OR cause_event_id IS NOT NULL),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id),
    FOREIGN KEY (tenant_id, cause_event_id) REFERENCES meeting_attendance_event (tenant_id, id)
);
CREATE INDEX meeting_quorum_change_idx ON meeting_quorum_change (tenant_id, meeting_id, level, recorded_at, id);
CREATE INDEX meeting_quorum_change_cause_idx ON meeting_quorum_change (tenant_id, cause_event_id);

-- A register row's keys, capacity and seat never change, and every change raises the version.
CREATE FUNCTION meeting_attendance_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF (NEW.tenant_id, NEW.meeting_id, NEW.user_id, NEW.capacity, NEW.for_member_id, NEW.for_user_id, NEW.opened_at)
            IS DISTINCT FROM (OLD.tenant_id, OLD.meeting_id, OLD.user_id, OLD.capacity, OLD.for_member_id, OLD.for_user_id,
                              OLD.opened_at)
        OR NEW.version <= OLD.version
    THEN
        RAISE EXCEPTION 'meeting_attendance: the keys and the capacity stay and every change raises the version'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION meeting_attendance_guard() FROM PUBLIC, memo_app;

CREATE TRIGGER meeting_attendance_keep BEFORE UPDATE ON meeting_attendance
    FOR EACH ROW EXECUTE FUNCTION meeting_attendance_guard();

ALTER TABLE meeting_attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_attendance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_attendance FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_attendance_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_attendance_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_attendance_event FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_item_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_item_progress FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_item_progress FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_quorum_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_quorum_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_quorum_change FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Nothing is deleted (design E05 §6); the events, the item in progress and the quorum's changes never change.
REVOKE DELETE, TRUNCATE ON meeting_attendance FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_attendance_event FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_item_progress FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_quorum_change FROM memo_app;
