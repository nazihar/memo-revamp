-- Phase 3 E05 T3 (design E05 §2, §3; flow H3): saved searches. Private to their owner (owner-only is enforced by the
-- application; RLS keeps them in their tenant), at most 50 per person. A saved search keeps criteria, never results: each
-- time it is opened it shows what the reader may read then.

CREATE TABLE search_saved (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    owner_id   uuid        NOT NULL,
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    criteria   jsonb       NOT NULL CHECK (jsonb_typeof(criteria) = 'object' AND octet_length(criteria::text) <= 4096),
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id)
);
CREATE INDEX search_saved_owner_idx ON search_saved (tenant_id, owner_id, name);

ALTER TABLE search_saved ENABLE ROW LEVEL SECURITY;
ALTER TABLE search_saved FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON search_saved FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
