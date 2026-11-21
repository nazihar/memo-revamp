-- Phase 4 E07 T3 (ADR-0067, design E07 §1.1, §1.14, §6, §7): the actions module's first migration. The action items of a
-- committee, their gapless reference counter per committee and year, the people involved beside the owner, and the
-- append-only timeline. Expand only: new tables, no data. Rows of other modules (committees, meetings, items, decisions,
-- users, positions, classification levels, workflow instances) are referred to by id and checked by the services
-- (ADR-0006); inside the module the foreign keys are composite (tenant_id, …).

-- The reference counter (design E07 §1.1): one row per committee and calendar year in the tenant's zone, taken with
-- INSERT … ON CONFLICT DO UPDATE … RETURNING in the issuing transaction, so a discarded draft takes no number and a
-- rollback gives the number back (AGENTS.md §4, ADR-0044's pattern).
CREATE TABLE actions_counter (
    tenant_id   uuid NOT NULL,
    forum_id    uuid NOT NULL,
    year        int  NOT NULL CHECK (year BETWEEN 2000 AND 9999),
    last_number int  NOT NULL CHECK (last_number >= 1),
    PRIMARY KEY (tenant_id, forum_id, year)
);

-- An action item (design E07 §1.1): raised as a DRAFT from a decision, an item, a meeting or directly, issued with its
-- reference and workflow instance, then kept in step with the instance by the engine's events. The item columns are the
-- source item's, copied from the latest published revision and refreshed when the meeting publishes again, so the one
-- visibility rule (ActionAccess, design E07 §1.14) runs on the action's own row. Free text (title, description, context,
-- the source note, reasons) is business content, never audited (the audit carries hashes).
CREATE TABLE actions_item (
    id                     uuid        NOT NULL,
    tenant_id              uuid        NOT NULL,
    forum_id               uuid        NOT NULL,
    -- Taken at issue: "BRC A14/2026" is the committee's code, number 14 of 2026.
    number_year            int         CHECK (number_year BETWEEN 2000 AND 9999),
    sequence               int         CHECK (sequence >= 1),
    reference              text        CHECK (char_length(reference) BETWEEN 1 AND 60),
    source                 text        NOT NULL CHECK (source IN ('DECISION', 'ITEM', 'MEETING', 'DIRECT')),
    meeting_id             uuid,
    item_id                uuid,
    decision_id            uuid,
    -- The decision's entry the action was raised from, and the condition line of that entry (0 is the first).
    decision_seq           int         CHECK (decision_seq >= 1),
    condition_index        int         CHECK (condition_index BETWEEN 0 AND 19),
    -- DIRECT: "Chair's instruction of 3 December".
    source_note            text        CHECK (char_length(source_note) <= 300),
    -- "BRC 3/2026, item 5 — decision, condition 2": the meeting's number and the item's, never its title.
    source_label           text        NOT NULL CHECK (char_length(source_label) BETWEEN 1 AND 300),
    item_parent_id         uuid,
    item_classification_id uuid,
    item_restricted_to     uuid[]      CHECK (cardinality(item_restricted_to) <= 200),
    item_title_hidden      boolean     NOT NULL DEFAULT false,
    item_members_only      boolean     NOT NULL DEFAULT false,
    title                  text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    description            text        CHECK (char_length(description) <= 4000),
    context                text        CHECK (char_length(context) <= 2000),
    classification_id      uuid        NOT NULL,
    owner_kind             text        CHECK (owner_kind IN ('PERSON', 'POSITION')),
    owner_user_id          uuid,
    owner_position_id      uuid,
    -- Who holds the owner's task now (the person, the position's holder or their substitute); null while it waits.
    holder_user_id         uuid,
    -- The definition step the owner's task is on, set by the first task of the instance (the owner's, ADR-0068).
    owner_step             text        CHECK (char_length(owner_step) BETWEEN 1 AND 40),
    priority               text        NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH')),
    due_date               date,
    original_due_date      date,
    due_revisions          int         NOT NULL DEFAULT 0 CHECK (due_revisions >= 0),
    amber_from             date,
    status                 text        NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'IN_PROGRESS', 'DONE_PENDING', 'CLOSED', 'CANCELLED')),
    confidence             text        CHECK (confidence IN ('ON_TRACK', 'AT_RISK', 'OFF_TRACK')),
    last_update_at         timestamptz,
    stale_from             date,
    rag_override           text        CHECK (rag_override IN ('RED', 'AMBER', 'GREEN')),
    rag_override_reason    text        CHECK (char_length(rag_override_reason) BETWEEN 1 AND 500),
    rag_override_by        uuid,
    rag_override_until     date,
    workflow_instance_id   uuid,
    step_name              text        CHECK (char_length(step_name) <= 200),
    done_at                timestamptz,
    closed_at              timestamptz,
    closed_by              uuid,
    cancel_kind            text        CHECK (cancel_kind IN ('NO_LONGER_NEEDED', 'SUPERSEDED', 'RISK_ACCEPTED', 'DISCARDED', 'DUPLICATE')),
    cancel_reason          text        CHECK (char_length(cancel_reason) BETWEEN 1 AND 500),
    superseded_by          uuid,
    reopen_count           int         NOT NULL DEFAULT 0 CHECK (reopen_count >= 0),
    created_by             uuid        NOT NULL,
    created_at             timestamptz NOT NULL,
    issued_by              uuid,
    issued_at              timestamptz,
    version                bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    -- The source's shape: a decision is on an item of a meeting; an item is on a meeting; a direct action has neither.
    CHECK (CASE source
               WHEN 'DECISION' THEN meeting_id IS NOT NULL AND item_id IS NOT NULL AND decision_id IS NOT NULL AND decision_seq IS NOT NULL
               WHEN 'ITEM' THEN meeting_id IS NOT NULL AND item_id IS NOT NULL AND decision_id IS NULL
               WHEN 'MEETING' THEN meeting_id IS NOT NULL AND item_id IS NULL AND decision_id IS NULL
               ELSE meeting_id IS NULL AND item_id IS NULL AND decision_id IS NULL
           END),
    CHECK (condition_index IS NULL OR decision_id IS NOT NULL),
    CHECK (source_note IS NULL OR source = 'DIRECT'),
    CHECK (item_id IS NOT NULL OR (item_parent_id IS NULL AND item_classification_id IS NULL AND item_restricted_to IS NULL)),
    -- Exactly one owner, of its kind.
    CHECK (CASE owner_kind
               WHEN 'PERSON' THEN owner_user_id IS NOT NULL AND owner_position_id IS NULL
               WHEN 'POSITION' THEN owner_position_id IS NOT NULL AND owner_user_id IS NULL
               ELSE owner_user_id IS NULL AND owner_position_id IS NULL
           END),
    -- The reference, its number and the issue are taken together, once; an issued action has its owner and dates.
    CHECK ((reference IS NULL) = (sequence IS NULL) AND (reference IS NULL) = (number_year IS NULL)
           AND (reference IS NULL) = (issued_at IS NULL) AND (issued_at IS NULL) = (issued_by IS NULL)),
    CHECK (reference IS NULL OR (owner_kind IS NOT NULL AND due_date IS NOT NULL AND original_due_date IS NOT NULL)),
    CHECK (reference IS NOT NULL OR status IN ('DRAFT', 'CANCELLED')),
    CHECK ((status = 'DRAFT') = (reference IS NULL AND cancel_kind IS NULL)),
    CHECK ((cancel_kind = 'DISCARDED') = (status = 'CANCELLED' AND reference IS NULL)),
    CHECK ((cancel_kind IS NULL) = (status <> 'CANCELLED')),
    CHECK (cancel_kind = 'SUPERSEDED' OR superseded_by IS NULL),
    CHECK (rag_override IS NOT NULL OR (rag_override_reason IS NULL AND rag_override_by IS NULL AND rag_override_until IS NULL)),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT actions_item_reference_uk UNIQUE (tenant_id, reference),
    CONSTRAINT actions_item_number_uk UNIQUE (tenant_id, forum_id, number_year, sequence),
    FOREIGN KEY (tenant_id, superseded_by) REFERENCES actions_item (tenant_id, id)
);
-- The committee's tracker: by status and due date (design E07 §7, §8).
CREATE INDEX actions_item_forum_idx ON actions_item (tenant_id, forum_id, status, due_date, id);
-- "My actions": the holder's open actions by due date.
CREATE INDEX actions_item_holder_idx ON actions_item (tenant_id, holder_user_id, status, due_date);
-- "My actions" for a person owner, and the actions of a position (its holder changes).
CREATE INDEX actions_item_owner_user_idx ON actions_item (tenant_id, owner_user_id) WHERE owner_user_id IS NOT NULL;
CREATE INDEX actions_item_owner_position_idx ON actions_item (tenant_id, owner_position_id) WHERE owner_position_id IS NOT NULL;
-- A meeting's actions, and refreshing their item columns at the meeting's next publication.
CREATE INDEX actions_item_meeting_idx ON actions_item (tenant_id, meeting_id);
-- A decision's actions (its correction is told to them).
CREATE INDEX actions_item_decision_idx ON actions_item (tenant_id, decision_id) WHERE decision_id IS NOT NULL;
-- The engine's events find the action of an instance.
CREATE INDEX actions_item_instance_idx ON actions_item (tenant_id, workflow_instance_id);
CREATE INDEX actions_item_superseded_idx ON actions_item (tenant_id, superseded_by) WHERE superseded_by IS NOT NULL;

-- The people involved beside the owner (design E07 §1.3): at most five at a time, each read the action and post updates.
-- Removing someone sets [removed_at] once; adding them again is a new row.
CREATE TABLE actions_contributor (
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    action_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    added_by   uuid        NOT NULL,
    added_at   timestamptz NOT NULL,
    removed_by uuid,
    removed_at timestamptz,
    CHECK ((removed_at IS NULL) = (removed_by IS NULL)),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, action_id) REFERENCES actions_item (tenant_id, id)
);
CREATE INDEX actions_contributor_action_idx ON actions_contributor (tenant_id, action_id);
-- One current row per person and action.
CREATE UNIQUE INDEX actions_contributor_current_uk ON actions_contributor (tenant_id, action_id, user_id) WHERE removed_at IS NULL;
-- "Contributing" in "My actions", and the visibility rule's involvement.
CREATE INDEX actions_contributor_user_idx ON actions_contributor (tenant_id, user_id) WHERE removed_at IS NULL;

-- The timeline (design E07 §1.6): append-only entries, never changed; a correction is a new entry pointing at the earlier
-- one. Free text is business content, never audited.
CREATE TABLE actions_entry (
    tenant_id    uuid        NOT NULL,
    action_id    uuid        NOT NULL,
    seq          int         NOT NULL CHECK (seq >= 1),
    kind         text        NOT NULL CHECK (kind ~ '^[A-Z][A-Z_]{1,39}$'),
    actor_id     uuid,
    on_behalf_of uuid,
    how          text        CHECK (how IN ('PHONE', 'EMAIL', 'IN_PERSON', 'MEETING')),
    text         text        CHECK (char_length(text) BETWEEN 1 AND 4000),
    confidence   text        CHECK (confidence IN ('ON_TRACK', 'AT_RISK', 'OFF_TRACK')),
    details      jsonb       NOT NULL DEFAULT '{}'::jsonb
                             CHECK (jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 4096),
    file_ids     uuid[]      NOT NULL DEFAULT '{}' CHECK (cardinality(file_ids) <= 10),
    corrects_seq int,
    at           timestamptz NOT NULL,
    CHECK (corrects_seq IS NULL OR corrects_seq < seq),
    PRIMARY KEY (tenant_id, action_id, seq),
    FOREIGN KEY (tenant_id, action_id) REFERENCES actions_item (tenant_id, id)
);

ALTER TABLE actions_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_counter FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE actions_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_item FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE actions_contributor ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_contributor FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_contributor FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE actions_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_entry FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The second line of defence behind the guarded updates (design E07 §6): an action keeps its committee, its source and its
-- creation; its reference, number, issue and original date are set once and never change; the version moves by one.
CREATE FUNCTION actions_item_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.forum_id <> OLD.forum_id OR NEW.source <> OLD.source
        OR NEW.meeting_id IS DISTINCT FROM OLD.meeting_id OR NEW.item_id IS DISTINCT FROM OLD.item_id
        OR NEW.decision_id IS DISTINCT FROM OLD.decision_id OR NEW.created_by <> OLD.created_by
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'action %: its committee, source and creation never change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF (OLD.reference IS NOT NULL AND (NEW.reference IS DISTINCT FROM OLD.reference OR NEW.sequence IS DISTINCT FROM OLD.sequence
            OR NEW.number_year IS DISTINCT FROM OLD.number_year OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
            OR NEW.issued_by IS DISTINCT FROM OLD.issued_by))
        OR (OLD.original_due_date IS NOT NULL AND NEW.original_due_date IS DISTINCT FROM OLD.original_due_date) THEN
        RAISE EXCEPTION 'action %: its reference, issue and original date are set once', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'action %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION actions_item_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER actions_item_guard BEFORE UPDATE ON actions_item FOR EACH ROW EXECUTE FUNCTION actions_item_guard();

-- A counter only moves forward, by one (gapless, AGENTS.md §4).
CREATE FUNCTION actions_counter_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.forum_id <> OLD.forum_id OR NEW.year <> OLD.year
        OR NEW.last_number <> OLD.last_number + 1 THEN
        RAISE EXCEPTION 'action counter %/%: numbers are taken one at a time', OLD.forum_id, OLD.year USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION actions_counter_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER actions_counter_guard BEFORE UPDATE ON actions_counter FOR EACH ROW EXECUTE FUNCTION actions_counter_guard();

-- A contributor's row changes only by being ended, once.
CREATE FUNCTION actions_contributor_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF OLD.removed_at IS NOT NULL OR NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.action_id <> OLD.action_id
        OR NEW.user_id <> OLD.user_id OR NEW.added_by <> OLD.added_by OR NEW.added_at <> OLD.added_at THEN
        RAISE EXCEPTION 'action contributor %: only ended, once', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION actions_contributor_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER actions_contributor_guard BEFORE UPDATE ON actions_contributor FOR EACH ROW EXECUTE FUNCTION actions_contributor_guard();

-- The record is sacred (AGENTS.md §3.3): nothing is deleted or truncated; the timeline is insert only; an action changes
-- only its mutable columns (the trigger guards the rest), a counter its number, a contributor its end.
REVOKE UPDATE, DELETE, TRUNCATE ON actions_counter, actions_item, actions_contributor, actions_entry FROM memo_app;
GRANT UPDATE (last_number) ON actions_counter TO memo_app;
GRANT UPDATE (removed_by, removed_at) ON actions_contributor TO memo_app;
GRANT UPDATE (number_year, sequence, reference, decision_seq, condition_index, source_note, source_label, item_parent_id,
              item_classification_id, item_restricted_to, item_title_hidden, item_members_only, title, description, context,
              classification_id, owner_kind, owner_user_id, owner_position_id, holder_user_id, owner_step, priority, due_date,
              original_due_date, due_revisions, amber_from, status, confidence, last_update_at, stale_from, rag_override,
              rag_override_reason, rag_override_by, rag_override_until, workflow_instance_id, step_name, done_at, closed_at,
              closed_by, cancel_kind, cancel_reason, superseded_by, reopen_count, issued_by, issued_at, version)
    ON actions_item TO memo_app;
