-- Phase 4 E04 T1 (ADR-0062, design E04 §1, §6, §7): declarations of interest on agenda items, their history, the review
-- confirmations and the reminders sent. Numbered V5, not V4: E03 T4 (members' reads, consent requests) takes meeting/V4
-- in parallel (E04 log, T1). Expand only: new tables, and one unique constraint on meeting_item so a declaration's item
-- must belong to its meeting (threat model E3). Nothing is ever deleted; the history and the reviews are append-only.

-- (tenant, meeting, item) is unique already (the item's id is); the constraint lets the foreign key below name the meeting.
ALTER TABLE meeting_item ADD CONSTRAINT meeting_item_meeting_uk UNIQUE (tenant_id, meeting_id, id);

-- One person's interest in one item of one meeting (design E04 §1.1). The row keeps the current state for the recusal
-- rule; every transition is a row in meeting_declaration_event.
CREATE TABLE meeting_declaration (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    meeting_id   uuid        NOT NULL,
    item_id      uuid        NOT NULL,
    -- Copied from the meeting, for the registers (T4).
    forum_id     uuid        NOT NULL,
    -- The declarant.
    user_id      uuid        NOT NULL,
    -- Business content: shown to the declarant and officers, never audited (the audit carries its SHA-256).
    nature       text        NOT NULL CHECK (length(nature) BETWEEN 1 AND 1000),
    intent       text        NOT NULL CHECK (intent IN ('LEAVE', 'ASK_CHAIR')),
    source       text        NOT NULL CHECK (source IN ('SELF', 'ON_BEHALF', 'MEMO', 'EARLIER_MEETING', 'REGISTER')),
    -- The memo (MEMO) or the earlier declaration (EARLIER_MEETING).
    source_ref   uuid,
    recorded_by  uuid,
    how          text        NOT NULL CHECK (how IN ('IN_APP', 'SYSTEM', 'AT_MEETING', 'IN_PERSON', 'EMAIL', 'PHONE')),
    how_note     text        CHECK (length(how_note) BETWEEN 1 AND 200),
    declared_at  timestamptz NOT NULL,
    at_meeting   boolean     NOT NULL,
    -- Recorded by a secretary after the meeting was held (a declaration made in the room).
    correction   boolean     NOT NULL DEFAULT false,
    -- The published revision the declarant was reading; null before publication.
    revision     int         CHECK (revision >= 1),
    state        text        NOT NULL CHECK (state IN ('RECUSED', 'AWAITING_RULING', 'TAKING_PART', 'WITHDRAWN')),
    -- The latest ruling: {outcome, by, onBehalfBy?, how, note?, at}.
    ruling       jsonb       CHECK (ruling IS NULL OR (jsonb_typeof(ruling) = 'object' AND octet_length(ruling::text) <= 4096)),
    version      bigint      NOT NULL DEFAULT 0,
    CHECK ((source = 'SELF') = (how = 'IN_APP')),
    CHECK ((source IN ('MEMO', 'EARLIER_MEETING')) = (how = 'SYSTEM')),
    CHECK ((source IN ('MEMO', 'EARLIER_MEETING')) = (source_ref IS NOT NULL)),
    CHECK (how_note IS NULL OR source = 'ON_BEHALF'),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id, item_id) REFERENCES meeting_item (tenant_id, meeting_id, id)
);
-- ONE_OPEN: at most one declaration per person and item that is not withdrawn.
CREATE UNIQUE INDEX meeting_declaration_open_uk ON meeting_declaration (tenant_id, item_id, user_id) WHERE state <> 'WITHDRAWN';
-- The recusal rule's anti-join (T2): effective declarations of a person.
CREATE INDEX meeting_declaration_effective_idx ON meeting_declaration (tenant_id, user_id, item_id)
    WHERE state IN ('RECUSED', 'AWAITING_RULING');
CREATE INDEX meeting_declaration_meeting_idx ON meeting_declaration (tenant_id, meeting_id, item_id);
-- The registers (T4): by forum, across forums, by person; newest first.
CREATE INDEX meeting_declaration_forum_idx ON meeting_declaration (tenant_id, forum_id, declared_at DESC, id);
CREATE INDEX meeting_declaration_declared_idx ON meeting_declaration (tenant_id, declared_at DESC, id);
CREATE INDEX meeting_declaration_user_idx ON meeting_declaration (tenant_id, user_id, declared_at DESC, id);

-- The history of a declaration (design E04 §1.2): one row per transition, never changed.
CREATE TABLE meeting_declaration_event (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    declaration_id uuid        NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('DECLARED', 'INTENT_CHANGED', 'RULED', 'WITHDRAWN')),
    state_after    text        NOT NULL CHECK (state_after IN ('RECUSED', 'AWAITING_RULING', 'TAKING_PART', 'WITHDRAWN')),
    -- Who acted (null for the system); for a ruling, the chair who gave it.
    actor_id       uuid,
    -- The secretary who recorded it for someone else (a ruling given by phone).
    on_behalf_by   uuid,
    how            text        NOT NULL CHECK (how IN ('IN_APP', 'SYSTEM', 'AT_MEETING', 'IN_PERSON', 'EMAIL', 'PHONE')),
    -- A ruling's note, or the reason of a withdrawal or a correction.
    note           text        CHECK (length(note) BETWEEN 1 AND 500),
    correction     boolean     NOT NULL DEFAULT false,
    at             timestamptz NOT NULL,
    CHECK (kind <> 'WITHDRAWN' OR (state_after = 'WITHDRAWN' AND note IS NOT NULL)),
    FOREIGN KEY (tenant_id, declaration_id) REFERENCES meeting_declaration (tenant_id, id)
);
CREATE INDEX meeting_declaration_event_declaration_idx ON meeting_declaration_event (tenant_id, declaration_id, at);

-- "I have declared my interests in this agenda" (design E04 §1.5), against a published revision.
CREATE TABLE meeting_declaration_review (
    tenant_id   uuid        NOT NULL,
    meeting_id  uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    revision    int         NOT NULL CHECK (revision >= 1),
    reviewed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id, user_id, revision),
    FOREIGN KEY (tenant_id, meeting_id, revision) REFERENCES meeting_revision (tenant_id, meeting_id, number)
);
CREATE INDEX meeting_declaration_review_revision_idx ON meeting_declaration_review (tenant_id, meeting_id, revision);

-- The declarations-due reminder sent (T4's sweep): once per person and revision.
CREATE TABLE meeting_declaration_reminder (
    tenant_id  uuid        NOT NULL,
    meeting_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    revision   int         NOT NULL CHECK (revision >= 1),
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id, user_id, revision),
    FOREIGN KEY (tenant_id, meeting_id, revision) REFERENCES meeting_revision (tenant_id, meeting_id, number)
);
CREATE INDEX meeting_declaration_reminder_revision_idx ON meeting_declaration_reminder (tenant_id, meeting_id, revision);

ALTER TABLE meeting_declaration ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_declaration FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_declaration FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_declaration_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_declaration_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_declaration_event FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_declaration_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_declaration_review FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_declaration_review FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_declaration_reminder ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_declaration_reminder FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_declaration_reminder FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The second line of defence behind the guarded update (design E04 §6): the nature, the declarant, the item and how it
-- was recorded never change, the version moves by one, and the state moves only along design E04 §1.2.
CREATE FUNCTION meeting_declaration_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.meeting_id <> OLD.meeting_id OR NEW.item_id <> OLD.item_id
        OR NEW.forum_id <> OLD.forum_id OR NEW.user_id <> OLD.user_id OR NEW.nature <> OLD.nature
        OR NEW.source <> OLD.source OR NEW.source_ref IS DISTINCT FROM OLD.source_ref
        OR NEW.recorded_by IS DISTINCT FROM OLD.recorded_by OR NEW.how <> OLD.how
        OR NEW.how_note IS DISTINCT FROM OLD.how_note OR NEW.declared_at <> OLD.declared_at
        OR NEW.at_meeting <> OLD.at_meeting OR NEW.correction <> OLD.correction
        OR NEW.revision IS DISTINCT FROM OLD.revision THEN
        RAISE EXCEPTION 'declaration %: only its state, intent and ruling change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'declaration %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NOT ((OLD.state, NEW.state) IN (
            ('AWAITING_RULING', 'RECUSED'), ('AWAITING_RULING', 'TAKING_PART'), ('RECUSED', 'AWAITING_RULING'),
            ('RECUSED', 'TAKING_PART'), ('TAKING_PART', 'RECUSED'), ('RECUSED', 'WITHDRAWN'),
            ('AWAITING_RULING', 'WITHDRAWN'), ('TAKING_PART', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION 'declaration %: % to % is not a transition', OLD.id, OLD.state, NEW.state USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION meeting_declaration_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER meeting_declaration_guard BEFORE UPDATE ON meeting_declaration FOR EACH ROW EXECUTE FUNCTION meeting_declaration_guard();

-- The record is sacred (AGENTS.md §3.3): nothing is deleted; the history, the reviews and the reminders are append-only;
-- a declaration changes only its state, intent, ruling and version.
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_declaration, meeting_declaration_event, meeting_declaration_review,
    meeting_declaration_reminder FROM memo_app;
GRANT UPDATE (state, intent, ruling, version) ON meeting_declaration TO memo_app;
