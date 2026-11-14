-- Phase 3 E01 T5 (ADR-0043, design E01 §1, §7): an approved memo can be closed (by its initiator, or automatically after
-- the days its type sets); memos link to each other; memo types keep their retention policy and attachment rules (acted
-- on by Phase 7 and E02). Expand: existing approved memos get their approval time from their last change.

ALTER TABLE memo DROP CONSTRAINT memo_status_check;
ALTER TABLE memo
    ADD CONSTRAINT memo_status_check CHECK (
        status IN ('DRAFT', 'IN_REVIEW', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'DISCARDED', 'CANCELLED', 'CLOSED')
    ),
    ADD COLUMN approved_at timestamptz,
    ADD COLUMN closed_at   timestamptz,
    ADD CONSTRAINT memo_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL));
-- Auto-close finds approved memos by when they were approved.
CREATE INDEX memo_tenant_approved_idx ON memo (tenant_id, approved_at) WHERE status = 'APPROVED';

ALTER TABLE memo_type_version
    ADD COLUMN retention        jsonb CHECK (retention IS NULL OR (jsonb_typeof(retention) = 'object' AND octet_length(retention::text) <= 1024)),
    ADD COLUMN attachment_rules jsonb NOT NULL DEFAULT '{}' CHECK (
        jsonb_typeof(attachment_rules) = 'object' AND octet_length(attachment_rules::text) <= 4096
    ),
    ADD COLUMN auto_close_days  int CHECK (auto_close_days BETWEEN 1 AND 3650);

CREATE TABLE memo_link (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    memo_id        uuid        NOT NULL,
    target_memo_id uuid        NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('REFERS_TO', 'SUPERSEDES', 'FOLLOWS_UP')),
    created_by     uuid        NOT NULL,
    created_at     timestamptz NOT NULL,
    -- A link is never deleted: its removal is recorded (the record is sacred, AGENTS §3.3).
    removed_by     uuid,
    removed_at     timestamptz,
    CHECK (memo_id <> target_memo_id),
    CHECK ((removed_by IS NULL) = (removed_at IS NULL)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id),
    FOREIGN KEY (tenant_id, target_memo_id) REFERENCES memo (tenant_id, id)
);
-- One standing link between two memos; both directions are read.
CREATE UNIQUE INDEX memo_link_standing_uk ON memo_link (tenant_id, memo_id, target_memo_id) WHERE removed_at IS NULL;
CREATE INDEX memo_link_tenant_memo_idx ON memo_link (tenant_id, memo_id);
CREATE INDEX memo_link_tenant_target_idx ON memo_link (tenant_id, target_memo_id);
ALTER TABLE memo_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE memo_link FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memo_link FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Backfill across tenants: FORCE binds the owner too and no tenant is set here, so lift it for this transaction.
ALTER TABLE memo NO FORCE ROW LEVEL SECURITY;
UPDATE memo SET approved_at = updated_at WHERE status = 'APPROVED';
ALTER TABLE memo FORCE ROW LEVEL SECURITY;
