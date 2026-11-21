-- Phase 4 E06 T4 (ADR-0065, ADR-0044, design E06 §1.3, §6, §7): resolution numbers. One counter per committee and period,
-- taken in the business transaction with INSERT … ON CONFLICT DO UPDATE … RETURNING and locked to commit (gapless: a
-- rollback gives the number back); one row per number given, never deleted: a number belongs to its decision for ever, a
-- void is recorded on the row and the number is never reused. Expand only: new tables, no data.

-- A committee's counter for a period (`ALL`, `2026`, `FY2026-27`). [first_number] is 1, or the starting number an
-- administrator set before the first number was taken (to continue a paper register at go-live, ADR-0044).
CREATE TABLE minutes_resolution_counter (
    tenant_id        uuid    NOT NULL,
    forum_id         uuid    NOT NULL,
    period           text    NOT NULL CHECK (period ~ '^(ALL|[0-9]{4}|FY[0-9]{4}-[0-9]{2})$'),
    last_number      bigint  NOT NULL CHECK (last_number >= 0),
    first_number     bigint  NOT NULL DEFAULT 1 CHECK (first_number >= 1),
    started_by_admin boolean NOT NULL DEFAULT false,
    CHECK (last_number >= first_number - 1),
    PRIMARY KEY (tenant_id, forum_id, period)
);

-- The numbers given (design E06 §1.3): the decision's while IN_FORCE; VOID once a correction (before the release) made its
-- decision something other than a resolution, with the correction's reason. A decision has at most one number in force; one
-- that becomes a resolution again takes a new number.
CREATE TABLE minutes_resolution (
    id                  uuid        NOT NULL,
    tenant_id           uuid        NOT NULL,
    decision_id         uuid        NOT NULL,
    forum_id            uuid        NOT NULL,
    meeting_id          uuid        NOT NULL,
    period              text        NOT NULL,
    sequence            bigint      NOT NULL CHECK (sequence >= 1),
    -- As rendered by the committee type's format pinned on the meeting.
    number              text        NOT NULL CHECK (char_length(number) BETWEEN 1 AND 100),
    numbered_at         timestamptz NOT NULL,
    state               text        NOT NULL DEFAULT 'IN_FORCE' CHECK (state IN ('IN_FORCE', 'VOID')),
    void_reason         text        CHECK (char_length(void_reason) BETWEEN 1 AND 500),
    voided_at           timestamptz,
    -- The decision's entry that voided it.
    voided_by_entry_seq int         CHECK (voided_by_entry_seq >= 2),
    CHECK ((state = 'VOID') = (voided_at IS NOT NULL)),
    CHECK ((state = 'VOID') = (void_reason IS NOT NULL)),
    CHECK ((state = 'VOID') = (voided_by_entry_seq IS NOT NULL)),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, decision_id) REFERENCES minutes_decision (tenant_id, id),
    FOREIGN KEY (tenant_id, forum_id, period) REFERENCES minutes_resolution_counter (tenant_id, forum_id, period),
    -- Gapless and unique per committee and period; unique as written in the tenant.
    CONSTRAINT minutes_resolution_sequence_uk UNIQUE (tenant_id, forum_id, period, sequence),
    CONSTRAINT minutes_resolution_number_uk UNIQUE (tenant_id, number)
);
-- One number in force per decision.
CREATE UNIQUE INDEX minutes_resolution_in_force_uk ON minutes_resolution (tenant_id, decision_id) WHERE state = 'IN_FORCE';
-- A decision's numbers (the foreign key), in force or void.
CREATE INDEX minutes_resolution_decision_idx ON minutes_resolution (tenant_id, decision_id);
-- A meeting's resolutions (the minutes, the decisions list).
CREATE INDEX minutes_resolution_meeting_idx ON minutes_resolution (tenant_id, meeting_id);

ALTER TABLE minutes_resolution_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_resolution_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_resolution_counter FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_resolution ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_resolution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_resolution FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- A counter only moves forward; where it started never changes (the register check counts from it).
CREATE FUNCTION minutes_resolution_counter_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.forum_id <> OLD.forum_id OR NEW.period <> OLD.period
        OR NEW.first_number <> OLD.first_number OR NEW.started_by_admin <> OLD.started_by_admin THEN
        RAISE EXCEPTION 'resolution counter %/%: where it starts never changes', OLD.forum_id, OLD.period
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.last_number < OLD.last_number THEN
        RAISE EXCEPTION 'resolution counter %/%: a number given is never given back', OLD.forum_id, OLD.period
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_resolution_counter_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_resolution_counter_guard BEFORE UPDATE ON minutes_resolution_counter
    FOR EACH ROW EXECUTE FUNCTION minutes_resolution_counter_guard();

-- A number never changes and is never freed: only IN_FORCE → VOID, once, with its reason.
CREATE FUNCTION minutes_resolution_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.decision_id <> OLD.decision_id OR NEW.forum_id <> OLD.forum_id
        OR NEW.meeting_id <> OLD.meeting_id OR NEW.period <> OLD.period OR NEW.sequence <> OLD.sequence
        OR NEW.number <> OLD.number OR NEW.numbered_at <> OLD.numbered_at THEN
        RAISE EXCEPTION 'resolution %: its number never changes', OLD.number USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state = 'VOID' THEN
        RAISE EXCEPTION 'resolution %: a void number stays void', OLD.number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_resolution_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_resolution_guard BEFORE UPDATE ON minutes_resolution FOR EACH ROW EXECUTE FUNCTION minutes_resolution_guard();

-- The record is sacred (AGENTS.md §3.3): nothing is deleted; a counter changes only its last number, a resolution only its
-- void.
REVOKE UPDATE, DELETE, TRUNCATE ON minutes_resolution_counter, minutes_resolution FROM memo_app;
GRANT UPDATE (last_number) ON minutes_resolution_counter TO memo_app;
GRANT UPDATE (state, void_reason, voided_at, voided_by_entry_seq) ON minutes_resolution TO memo_app;
