-- Phase 3 E04 T4 (ADR-0053, design E04 §2, §10): the final record of an approved memo. One row per record version: its
-- state while it is built, then the stored file (purpose memo.rendition, object-locked), its hash, its validation and
-- how it was made. A READY version never changes; regeneration adds a version. The parts table says what went in, in
-- order, and is append-only. Expand only: memos approved before E04 get their records from the backfill job.

CREATE TABLE memo_rendition (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    memo_id              uuid        NOT NULL,
    version              int         NOT NULL CHECK (version >= 1),
    state                text        NOT NULL CHECK (state IN ('PENDING', 'BUILDING', 'READY', 'FAILED')),
    -- APPROVED (the approval), BACKFILL (approved before E04), REGENERATE (by a person, with a reason), RETRY.
    trigger              text        NOT NULL CHECK (trigger IN ('APPROVED', 'BACKFILL', 'REGENERATE', 'RETRY')),
    reason               text        CHECK (reason IS NULL OR length(reason) BETWEEN 1 AND 500),
    requested_by         uuid,
    file_id              uuid,
    file_version_id      uuid,
    -- Lower-case hex SHA-256 of the record's plaintext.
    sha256               text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes           bigint      CHECK (size_bytes > 0),
    pages                int         CHECK (pages > 0),
    classification_id    uuid,
    classification_rank  int         CHECK (classification_rank >= 0),
    compliant            boolean,
    -- {profile, validator, failedRules: {rule: count}, error}
    validation           jsonb       CHECK (validation IS NULL OR (jsonb_typeof(validation) = 'object' AND octet_length(validation::text) <= 16384)),
    -- {rasterisedPages, placeholders, missingGlyphs, textMissing: [...]}
    flags                jsonb       CHECK (flags IS NULL OR (jsonb_typeof(flags) = 'object' AND octet_length(flags::text) <= 16384)),
    -- {renderer, converter, validator, font}
    renderer             jsonb       CHECK (renderer IS NULL OR (jsonb_typeof(renderer) = 'object' AND octet_length(renderer::text) <= 4096)),
    attempts             int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    error                text        CHECK (error IS NULL OR error ~ '^[A-Z0-9_]{1,40}$'),
    created_at           timestamptz NOT NULL,
    finished_at          timestamptz,
    CHECK ((trigger = 'REGENERATE') = (reason IS NOT NULL)),
    CHECK (state <> 'READY' OR (file_version_id IS NOT NULL AND sha256 IS NOT NULL AND compliant IS NOT NULL)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, memo_id, version),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
-- At most one version of a memo's record is being made at a time.
CREATE UNIQUE INDEX memo_rendition_one_open_idx ON memo_rendition (tenant_id, memo_id) WHERE state IN ('PENDING', 'BUILDING');
CREATE INDEX memo_rendition_ready_idx ON memo_rendition (tenant_id, created_at) WHERE state = 'READY';

CREATE TABLE memo_rendition_part (
    tenant_id          uuid    NOT NULL,
    rendition_id       uuid    NOT NULL,
    position           int     NOT NULL CHECK (position >= 0),
    kind               text    NOT NULL CHECK (kind IN ('COVER', 'DOCUMENT', 'APPROVALS', 'NOTES', 'ATTACHMENT', 'PLACEHOLDER')),
    title              text    NOT NULL CHECK (length(title) BETWEEN 1 AND 500),
    source_version_id  uuid,
    source_sha256      text    CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    first_page         int     NOT NULL CHECK (first_page >= 0),
    pages              int     NOT NULL CHECK (pages >= 0),
    mode               text    NOT NULL CHECK (mode IN ('GENERATED', 'NORMALISED', 'RASTERISED', 'IMAGE', 'PLACEHOLDER')),
    PRIMARY KEY (tenant_id, rendition_id, position),
    FOREIGN KEY (tenant_id, rendition_id) REFERENCES memo_rendition (tenant_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['memo_rendition', 'memo_rendition_part']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;

-- A record version changes only while it is made; its parts never do.
REVOKE UPDATE, DELETE, TRUNCATE ON memo_rendition FROM memo_app;
GRANT UPDATE (state, file_id, file_version_id, sha256, size_bytes, pages, classification_id, classification_rank, compliant,
              validation, flags, renderer, attempts, error, finished_at) ON memo_rendition TO memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON memo_rendition_part FROM memo_app;
