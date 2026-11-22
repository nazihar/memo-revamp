-- Phase 4 E07 T6 (ADR-0067, design E07 §1.11, §6, §7): follow-up memos. A memo raised from an action as a draft (RAISED)
-- or an existing memo linked to it (LINKED); at most ten per action, one row per memo and action. The memo is the memo
-- module's row, referred to by id and checked by the services (ADR-0006); the memo shows where it came from through its
-- MemoOrigins port, which the actions module answers from these rows. Expand only: a new table, no data. Needs V2 (T4)
-- before it: Flyway applies the module's versions in order.
CREATE TABLE actions_memo (
    tenant_id uuid        NOT NULL,
    action_id uuid        NOT NULL,
    memo_id   uuid        NOT NULL,
    kind      text        NOT NULL CHECK (kind IN ('RAISED', 'LINKED')),
    linked_by uuid        NOT NULL,
    linked_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, action_id, memo_id),
    FOREIGN KEY (tenant_id, action_id) REFERENCES actions_item (tenant_id, id)
);
-- The memo's side: "Raised from action …" on the memo, and the actions told when the memo is decided.
CREATE INDEX actions_memo_memo_idx ON actions_memo (tenant_id, memo_id);

ALTER TABLE actions_memo ENABLE ROW LEVEL SECURITY;
ALTER TABLE actions_memo FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON actions_memo FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The record is sacred (AGENTS.md §3.3): a link is written once, never changed or removed.
REVOKE UPDATE, DELETE, TRUNCATE ON actions_memo FROM memo_app;
