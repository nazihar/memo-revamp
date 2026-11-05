-- Files service (ADR-0028, ADR-0029). A file has immutable versions; each version's content is encrypted with the tenant's
-- streaming keyset and stored under a random object key, in the object-locked `records` bucket (record purposes) or in
-- `work`. Nothing here is deleted by the application except pending-upload rows. No FKs to other modules' tables: the
-- owner id is interpreted by the purpose, user ids are checked by the services.

-- One Tink keyset per tenant, encrypted under a key-encryption key (KEK) from the key provider, with the tenant id as
-- associated data. Created on first use; never deleted by the application (crypto-shredding arrives with off-boarding).
CREATE TABLE files_tenant_keyset (
    tenant_id        uuid        PRIMARY KEY,
    encrypted_keyset bytea       NOT NULL CHECK (length(encrypted_keyset) <= 65536),
    kek_id           text        NOT NULL CHECK (length(kek_id) BETWEEN 1 AND 200),
    primary_key_id   bigint      NOT NULL,
    created_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0
);

CREATE TABLE files_file (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    purpose            text        NOT NULL CHECK (purpose ~ '^[a-z][a-z0-9.-]{0,99}$'),
    owner_id           text        CHECK (length(owner_id) BETWEEN 1 AND 200),
    current_version_id uuid,
    version_count      int         NOT NULL DEFAULT 0 CHECK (version_count >= 0),
    created_by         uuid,
    created_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK ((version_count = 0) = (current_version_id IS NULL))
);
CREATE INDEX files_file_tenant_owner_idx ON files_file (tenant_id, purpose, owner_id);

CREATE TABLE files_version (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    file_id          uuid        NOT NULL,
    number           int         NOT NULL CHECK (number >= 1),
    original_name    text        NOT NULL CHECK (length(original_name) BETWEEN 1 AND 255),
    media_type       text        NOT NULL CHECK (length(media_type) BETWEEN 3 AND 200),
    size_bytes       bigint      NOT NULL CHECK (size_bytes >= 0),
    sha256           bytea       NOT NULL CHECK (length(sha256) = 32),
    bucket           text        NOT NULL CHECK (bucket IN ('records', 'work')),
    object_key       text        NOT NULL CHECK (object_key ~ '^t/[0-9a-f-]{36}/[a-z2-7]{26}$'),
    -- The storage version written at upload: reads and retention changes address it, so a newer version written under
    -- the same key (possible in a versioned bucket) can never shadow the record. Null where the bucket is unversioned.
    object_version   text        CHECK (length(object_version) BETWEEN 1 AND 1024),
    ciphertext_bytes bigint      NOT NULL CHECK (ciphertext_bytes > 0),
    keyset_key_id    bigint      NOT NULL,
    scan_engine      text        NOT NULL CHECK (length(scan_engine) BETWEEN 1 AND 200),
    pdf_pages        int         CHECK (pdf_pages >= 0),
    lock_state       text        NOT NULL CHECK (lock_state IN ('NONE', 'PROVISIONAL', 'FINAL')),
    retain_until     timestamptz,
    retention_mode   text        CHECK (retention_mode IN ('GOVERNANCE', 'COMPLIANCE')),
    uploaded_by      uuid,
    uploaded_at      timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, file_id, number),
    UNIQUE (tenant_id, bucket, object_key),
    FOREIGN KEY (tenant_id, file_id) REFERENCES files_file (tenant_id, id),
    -- Record versions are always locked (provisionally until the commit is through); ordinary versions never are.
    CHECK ((bucket = 'records') = (lock_state <> 'NONE')),
    CHECK ((lock_state = 'NONE') = (retain_until IS NULL AND retention_mode IS NULL)),
    CHECK (bucket <> 'records' OR object_version IS NOT NULL)
);
CREATE INDEX files_version_tenant_provisional_idx ON files_version (tenant_id, uploaded_at) WHERE lock_state = 'PROVISIONAL';

ALTER TABLE files_file ADD FOREIGN KEY (tenant_id, current_version_id) REFERENCES files_version (tenant_id, id);
CREATE INDEX files_file_tenant_current_idx ON files_file (tenant_id, current_version_id);

-- An object written to storage whose business transaction has not (yet) committed. Deleted by that transaction; rows
-- left behind are resolved by the pending sweep (design §12).
CREATE TABLE files_pending_upload (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    bucket            text        NOT NULL CHECK (bucket IN ('records', 'work')),
    object_key        text        NOT NULL CHECK (object_key ~ '^t/[0-9a-f-]{36}/[a-z2-7]{26}$'),
    created_at        timestamptz NOT NULL,
    provisional_until timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, bucket, object_key)
);
CREATE INDEX files_pending_upload_tenant_created_idx ON files_pending_upload (tenant_id, created_at);

-- Versions are append-only: the only change allowed is PROVISIONAL → FINAL of the lock state; nothing is deleted.
REVOKE DELETE, TRUNCATE ON files_version FROM memo_app;
REVOKE DELETE, TRUNCATE ON files_tenant_keyset FROM memo_app;

CREATE FUNCTION files_version_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.lock_state = 'PROVISIONAL' AND NEW.lock_state = 'FINAL'
       AND (to_jsonb(NEW) - 'lock_state') = (to_jsonb(OLD) - 'lock_state') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'files_version is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;
REVOKE EXECUTE ON FUNCTION files_version_guard() FROM PUBLIC, memo_app;

CREATE TRIGGER files_version_append_only BEFORE UPDATE OR DELETE ON files_version
    FOR EACH ROW EXECUTE FUNCTION files_version_guard();
CREATE TRIGGER files_version_no_truncate BEFORE TRUNCATE ON files_version
    FOR EACH STATEMENT EXECUTE FUNCTION files_version_guard();

ALTER TABLE files_tenant_keyset ENABLE ROW LEVEL SECURITY;
ALTER TABLE files_tenant_keyset FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON files_tenant_keyset FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE files_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE files_file FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON files_file FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE files_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE files_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON files_version FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE files_pending_upload ENABLE ROW LEVEL SECURITY;
ALTER TABLE files_pending_upload FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON files_pending_upload FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
