-- Phase 3 E03 T5 (ADR-0050, design E03 §5): copy-to, deliveries, read receipts and distribution lists.
-- A copy names a person, a position or a list and when it goes (at submit, or after final approval). Sending resolves it
-- to people, writes one delivery per person (sent, or refused when not cleared or inactive) and makes the sent ones
-- COPY participants. A receipt records the first time a copy recipient or an approver opens the memo.

CREATE TABLE memo_distribution_list (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    active     boolean     NOT NULL DEFAULT true,
    version    bigint      NOT NULL DEFAULT 0,
    created_by uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX memo_distribution_list_name_idx ON memo_distribution_list (tenant_id, lower(name)) WHERE active;

CREATE TABLE memo_distribution_member (
    tenant_id uuid NOT NULL,
    list_id   uuid NOT NULL,
    kind      text NOT NULL CHECK (kind IN ('USER', 'POSITION')),
    member_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, list_id, kind, member_id),
    FOREIGN KEY (tenant_id, list_id) REFERENCES memo_distribution_list (tenant_id, id)
);

CREATE TABLE memo_copy (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    memo_id        uuid        NOT NULL,
    recipient_kind text        NOT NULL CHECK (recipient_kind IN ('USER', 'POSITION', 'LIST')),
    recipient_id   uuid        NOT NULL,
    send_when      text        NOT NULL CHECK (send_when IN ('AT_SUBMIT', 'AFTER_APPROVAL')),
    added_by       uuid        NOT NULL,
    added_at       timestamptz NOT NULL,
    sent_at        timestamptz,
    -- Removed from the list before it was sent; a sent copy is never removed.
    removed_at     timestamptz,
    removed_by     uuid,
    CHECK ((removed_at IS NULL) = (removed_by IS NULL)),
    CHECK (sent_at IS NULL OR removed_at IS NULL),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX memo_copy_tenant_memo_idx ON memo_copy (tenant_id, memo_id, added_at);
CREATE UNIQUE INDEX memo_copy_one_standing_idx
    ON memo_copy (tenant_id, memo_id, recipient_kind, recipient_id) WHERE removed_at IS NULL;

CREATE TABLE memo_copy_delivery (
    tenant_id  uuid        NOT NULL,
    copy_id    uuid        NOT NULL,
    memo_id    uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    outcome    text        NOT NULL CHECK (outcome IN ('SENT', 'NOT_CLEARED', 'INACTIVE')),
    at         timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, copy_id, user_id),
    FOREIGN KEY (tenant_id, copy_id) REFERENCES memo_copy (tenant_id, id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX memo_copy_delivery_tenant_memo_idx ON memo_copy_delivery (tenant_id, memo_id, user_id);

CREATE TABLE memo_receipt (
    tenant_id        uuid        NOT NULL,
    memo_id          uuid        NOT NULL,
    user_id          uuid        NOT NULL,
    opened_at        timestamptz NOT NULL,
    -- The document version the memo page showed; null when it had no document.
    document_version int,
    PRIMARY KEY (tenant_id, memo_id, user_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['memo_distribution_list', 'memo_distribution_member', 'memo_copy', 'memo_copy_delivery', 'memo_receipt']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;

-- Deliveries and receipts are records: written once, never changed (AGENTS §3.3). A copy changes only when it is sent
-- or removed before sending.
REVOKE UPDATE, DELETE, TRUNCATE ON memo_copy_delivery FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON memo_receipt FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON memo_copy FROM memo_app;
GRANT UPDATE (sent_at, removed_at, removed_by) ON memo_copy TO memo_app;
REVOKE TRUNCATE ON memo_distribution_list, memo_distribution_member FROM memo_app;
REVOKE DELETE ON memo_distribution_list FROM memo_app;
