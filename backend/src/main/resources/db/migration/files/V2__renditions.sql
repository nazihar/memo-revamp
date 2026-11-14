-- Phase 3 E02 T2 (ADR-0045): derived content of a file version, a PDF to preview and the extracted text. Built by
-- background jobs; encrypted with the tenant's keyset in the `work` bucket (rebuildable, so not a record).
CREATE TABLE files_rendition (
    tenant_id        uuid        NOT NULL,
    version_id       uuid        NOT NULL,
    kind             text        NOT NULL CHECK (kind IN ('PDF', 'TEXT')),
    state            text        NOT NULL CHECK (state IN ('PENDING', 'DONE', 'FAILED', 'NOT_POSSIBLE')),
    attempts         int         NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 100),
    -- Why it failed or is not possible: a code such as TIMEOUT, SIZE, PAGES, UNSUPPORTED, NO_SERVICE.
    error            text        CHECK (error ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    object_key       text        CHECK (object_key ~ '^t/[0-9a-f-]{36}/r/[a-z2-7]{26}$'),
    size_bytes       bigint      CHECK (size_bytes >= 0),
    ciphertext_bytes bigint      CHECK (ciphertext_bytes > 0),
    sha256           bytea       CHECK (length(sha256) = 32),
    keyset_key_id    bigint,
    pages            int         CHECK (pages >= 0),
    -- The text was cut at the extraction limit.
    truncated        boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL,
    finished_at      timestamptz,
    PRIMARY KEY (tenant_id, version_id, kind),
    FOREIGN KEY (tenant_id, version_id) REFERENCES files_version (tenant_id, id),
    CHECK ((state = 'DONE') = (object_key IS NOT NULL)),
    CHECK (state <> 'DONE' OR (size_bytes IS NOT NULL AND ciphertext_bytes IS NOT NULL AND sha256 IS NOT NULL
                                AND keyset_key_id IS NOT NULL AND finished_at IS NOT NULL)),
    CHECK (state NOT IN ('FAILED', 'NOT_POSSIBLE') OR error IS NOT NULL)
);
-- The recovery job finds rows whose job was lost.
CREATE INDEX files_rendition_pending_idx ON files_rendition (tenant_id, created_at) WHERE state = 'PENDING';

-- Renditions are rebuilt, never deleted by the application.
REVOKE DELETE, TRUNCATE ON files_rendition FROM memo_app;

ALTER TABLE files_rendition ENABLE ROW LEVEL SECURITY;
ALTER TABLE files_rendition FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON files_rendition FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
