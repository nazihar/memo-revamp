-- Phase 3 E01 T2 (ADR-0043, design E01 §1, §7): memo-type fields and who may raise a type, kept on the version; memo
-- metadata, and the version a memo is pinned to from its submit on. Expand; existing submitted memos are pinned to their
-- type's published version (version 1 since memo/V6).

ALTER TABLE memo_type_version
    ADD COLUMN fields      jsonb NOT NULL DEFAULT '[]' CHECK (
        jsonb_typeof(fields) = 'array' AND octet_length(fields::text) <= 65536
    ),
    ADD COLUMN raise_scope jsonb NOT NULL DEFAULT '{}' CHECK (
        jsonb_typeof(raise_scope) = 'object' AND octet_length(raise_scope::text) <= 16384
    );

ALTER TABLE memo
    ADD COLUMN memo_type_version_id uuid,
    ADD COLUMN metadata             jsonb NOT NULL DEFAULT '{}' CHECK (
        jsonb_typeof(metadata) = 'object' AND octet_length(metadata::text) <= 65536
    ),
    ADD CONSTRAINT memo_type_version_fk
        FOREIGN KEY (tenant_id, memo_type_version_id) REFERENCES memo_type_version (tenant_id, id);
CREATE INDEX memo_tenant_type_version_idx ON memo (tenant_id, memo_type_version_id);

-- Once pinned, a memo's type, version and metadata never change (threat model T1, T2): the record of what was submitted.
CREATE FUNCTION memo_pinned_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF OLD.memo_type_version_id IS NOT NULL AND (
        NEW.memo_type_version_id IS DISTINCT FROM OLD.memo_type_version_id
        OR NEW.memo_type_id IS DISTINCT FROM OLD.memo_type_id
        OR NEW.metadata IS DISTINCT FROM OLD.metadata
    ) THEN
        RAISE EXCEPTION 'memo % is pinned to its type version; its type and metadata cannot change', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION memo_pinned_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER memo_pinned_guard BEFORE UPDATE ON memo FOR EACH ROW EXECUTE FUNCTION memo_pinned_guard();

-- Backfill across tenants: FORCE binds the owner too and no tenant is set here, so lift it for this transaction.
ALTER TABLE memo NO FORCE ROW LEVEL SECURITY;
ALTER TABLE memo_type_version NO FORCE ROW LEVEL SECURITY;

UPDATE memo m
SET memo_type_version_id = v.id
FROM memo_type_version v
WHERE v.tenant_id = m.tenant_id
  AND v.memo_type_id = m.memo_type_id
  AND v.state = 'PUBLISHED'
  AND m.status NOT IN ('DRAFT', 'DISCARDED');

ALTER TABLE memo FORCE ROW LEVEL SECURITY;
ALTER TABLE memo_type_version FORCE ROW LEVEL SECURITY;
