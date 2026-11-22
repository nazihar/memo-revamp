-- Phase 4 E07 T7 (ADR-0067 C1, design E07 §1.12, §6, §7): the action taken report under "Matters arising", the actions a
-- secretary holds back from it, and the key of "Ask owners for an update". Expand only: new tables, no data. The report is
-- built in the transaction that publishes an agenda revision with a MATTERS_ARISING item, and frozen: a later revision
-- gets a report of its own and earlier ones stay as they were. Rows are snapshots of what the meeting is shown, with the
-- action's item columns and level copied, so each reader gets their rows by the one rule (ActionAccess, design §1.14).
-- Holds back and the ask key are insert only.
--
-- actions/V3 is E07 T6's (follow-up memos, PR #230): Flyway applies V3 before V4, so T6 merges first.

-- One report per published revision (design E07 §1.12): the meeting, its committee, the MATTERS_ARISING item it hangs on,
-- the previous meeting whose start bounds "since the last meeting", and what was built. [row_count] counts the rows shown
-- (sections 1-6, at most 500); [more_count] the rows left out beyond that cap; [held_count] the actions held back.
CREATE TABLE actions_report (
    id                  uuid        NOT NULL,
    tenant_id           uuid        NOT NULL,
    forum_id            uuid        NOT NULL,
    meeting_id          uuid        NOT NULL,
    revision            int         NOT NULL CHECK (revision >= 1),
    item_id             uuid        NOT NULL,
    previous_meeting_id uuid,
    since               timestamptz,
    built_at            timestamptz NOT NULL,
    row_count           int         NOT NULL CHECK (row_count BETWEEN 0 AND 500),
    more_count          int         NOT NULL CHECK (more_count >= 0),
    held_count          int         NOT NULL CHECK (held_count >= 0),
    -- SHA-256 of the rows as built (the snapshots in order), audited with `actions.report.built`.
    content_sha256      text        NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK ((previous_meeting_id IS NULL) = (since IS NULL)),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT actions_report_revision_uk UNIQUE (tenant_id, meeting_id, revision)
);

-- A row of a report (design E07 §1.12): its section and place, the action it snapshots (none for a correction to earlier
-- minutes, section 6, from E06 T8), the snapshot shown, and the action's item columns and level as they were, which the
-- rule reads. HELD_BACK and MORE rows carry no snapshot: they are counted, by the same rule, for officers ("1 action held
-- back by the secretary") and for everyone ("and 12 more open actions"), never shown.
CREATE TABLE actions_report_row (
    tenant_id              uuid    NOT NULL,
    report_id              uuid    NOT NULL,
    position               int     NOT NULL CHECK (position >= 1),
    section                text    NOT NULL CHECK (section IN ('TO_CLOSE', 'OVERDUE', 'OPEN', 'CLOSED', 'CANCELLED', 'CORRECTIONS',
                                                               'HELD_BACK', 'MORE')),
    action_id              uuid,
    snapshot               jsonb   NOT NULL DEFAULT '{}'::jsonb
                                   CHECK (jsonb_typeof(snapshot) = 'object' AND octet_length(snapshot::text) <= 16384),
    item_id                uuid,
    item_parent_id         uuid,
    item_classification_id uuid,
    item_restricted_to     uuid[]  CHECK (cardinality(item_restricted_to) <= 200),
    item_title_hidden      boolean NOT NULL DEFAULT false,
    item_members_only      boolean NOT NULL DEFAULT false,
    classification_id      uuid    NOT NULL,
    CHECK ((action_id IS NULL) = (section = 'CORRECTIONS')),
    CHECK (item_id IS NOT NULL OR (item_parent_id IS NULL AND item_classification_id IS NULL AND item_restricted_to IS NULL)),
    PRIMARY KEY (tenant_id, report_id, position),
    FOREIGN KEY (tenant_id, report_id) REFERENCES actions_report (tenant_id, id),
    FOREIGN KEY (tenant_id, action_id) REFERENCES actions_item (tenant_id, id)
);
CREATE INDEX actions_report_row_action_idx ON actions_report_row (tenant_id, action_id) WHERE action_id IS NOT NULL;

-- An action a secretary holds back from a meeting's report (design E07 §1.12, `POST /actions/{id}/report-hold`), with a
-- reason: every report built for that meeting afterwards leaves it out and counts it for officers. It stays open in the
-- tracker. The reason is business content, never audited (the audit carries its hash).
CREATE TABLE actions_report_hold (
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    action_id  uuid        NOT NULL,
    meeting_id uuid        NOT NULL,
    reason     text        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    held_by    uuid        NOT NULL,
    held_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT actions_report_hold_uk UNIQUE (tenant_id, meeting_id, action_id),
    FOREIGN KEY (tenant_id, action_id) REFERENCES actions_item (tenant_id, id)
);
CREATE INDEX actions_report_hold_action_idx ON actions_report_hold (tenant_id, action_id);

-- "Ask owners for an update" (design E07 §1.12): at most once a day per committee (409 ALREADY_ASKED), keyed here.
CREATE TABLE actions_update_ask (
    tenant_id uuid        NOT NULL,
    forum_id  uuid        NOT NULL,
    day       date        NOT NULL,
    asked_by  uuid        NOT NULL,
    asked_at  timestamptz NOT NULL,
    asked     int         NOT NULL CHECK (asked >= 0),
    PRIMARY KEY (tenant_id, forum_id, day)
);

ALTER TABLE actions_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_report FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_report FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE actions_report_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_report_row FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_report_row FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE actions_report_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_report_hold FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_report_hold FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE actions_update_ask ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_update_ask FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_update_ask FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The record is sacred (AGENTS.md §3.3): a report is what the meeting was shown, so its rows, its holds and the ask key
-- are insert only; nothing is deleted or truncated.
REVOKE UPDATE, DELETE, TRUNCATE ON actions_report, actions_report_row, actions_report_hold, actions_update_ask FROM memo_app;
