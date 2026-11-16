-- Phase 3 E06 T5 (ADR-0055, design E06 §3.2): the legacy import's batches, the originals received, and the staging tables
-- the dry run fills (T6). No foreign keys to other modules' tables (each module migrates alone, ADR-0007): memo, file,
-- user and level ids are checked by the services. Staging rows are kept after a batch closes (the chain of custody); only
-- the staged objects are purged.

CREATE TABLE imports_batch (
    id                        uuid        PRIMARY KEY,
    tenant_id                 uuid        NOT NULL,
    name                      text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    source_code               text        NOT NULL CHECK (source_code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    -- An IANA time zone: the legacy dates are days in it.
    time_zone                 text        NOT NULL CHECK (length(time_zone) BETWEEN 1 AND 64),
    default_classification_id uuid,
    number_prefix             text        CHECK (number_prefix ~ '^[A-Za-z0-9][A-Za-z0-9/._-]{0,19}$'),
    state                     text        NOT NULL DEFAULT 'OPEN' CHECK (state IN (
        'OPEN', 'DRY_RUN', 'VALIDATED', 'IMPORTING', 'STOPPING', 'STOPPED', 'VERIFYING', 'COMPLETED', 'CLOSED', 'ABANDONED'
    )),
    version                   bigint      NOT NULL DEFAULT 0,
    created_by                uuid        NOT NULL,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    -- The metadata: one workbook, or CSV files (one per table). Each is a staged version of purpose imports.source.
    source_kind               text        CHECK (source_kind IN ('XLSX', 'CSV')),
    workbook_file_id          uuid,
    workbook_version_id       uuid,
    memos_file_id             uuid,
    memos_version_id          uuid,
    files_file_id             uuid,
    files_version_id          uuid,
    approvals_file_id         uuid,
    approvals_version_id      uuid,
    input_hash                bytea       CHECK (length(input_hash) = 32),
    dry_run_hash              bytea       CHECK (length(dry_run_hash) = 32),
    dry_run_at                timestamptz,
    started_by                uuid,
    started_at                timestamptz,
    step_up_at                timestamptz,
    finished_at               timestamptz,
    completed_at              timestamptz,
    closed_at                 timestamptz,
    close_reason              text        CHECK (length(close_reason) BETWEEN 1 AND 500),
    -- The highest classification rank the batch writes (from the dry run).
    max_rank                  int         CHECK (max_rank >= 0),
    totals                    jsonb       CHECK (jsonb_typeof(totals) = 'object' AND octet_length(totals::text) <= 16384),
    report                    jsonb       CHECK (jsonb_typeof(report) = 'object' AND octet_length(report::text) <= 16384),
    CHECK ((workbook_file_id IS NULL) = (workbook_version_id IS NULL)),
    CHECK ((memos_file_id IS NULL) = (memos_version_id IS NULL)),
    CHECK ((files_file_id IS NULL) = (files_version_id IS NULL)),
    CHECK ((approvals_file_id IS NULL) = (approvals_version_id IS NULL)),
    -- A workbook replaces the CSV files and the reverse.
    CHECK (workbook_version_id IS NULL OR (memos_version_id IS NULL AND files_version_id IS NULL AND approvals_version_id IS NULL)),
    CHECK ((source_kind = 'XLSX') = (workbook_version_id IS NOT NULL)),
    UNIQUE (tenant_id, id)
);
CREATE INDEX imports_batch_list_idx ON imports_batch (tenant_id, created_at DESC, id DESC);
CREATE INDEX imports_batch_state_idx ON imports_batch (tenant_id, state);
-- One running import per tenant (design E06 §4.1).
CREATE UNIQUE INDEX imports_batch_one_running ON imports_batch (tenant_id) WHERE state IN ('IMPORTING', 'STOPPING', 'VERIFYING');

-- An original received (design E06 §4.3): a path the admin registered and, once uploaded, its staged version.
CREATE TABLE imports_file (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    batch_id          uuid        NOT NULL,
    -- NFC, '/' separators, no empty, '.' or '..' segment (PathRules).
    path              text        NOT NULL CHECK (length(path) BETWEEN 1 AND 1024),
    -- Case-folded (it may be longer than the path: "ß" folds to "ss").
    path_fold         text        NOT NULL CHECK (length(path_fold) BETWEEN 1 AND 4096),
    -- As registered; the received size once stored.
    size_bytes        bigint      NOT NULL CHECK (size_bytes >= 0),
    state             text        NOT NULL DEFAULT 'EXPECTED' CHECK (state IN ('EXPECTED', 'RECEIVED', 'REFUSED', 'PURGED')),
    file_id           uuid,
    version_id        uuid,
    sha256            bytea       CHECK (length(sha256) = 32),
    media_type        text        CHECK (length(media_type) BETWEEN 1 AND 200),
    -- The pipeline's reason code, and the scanner's signature when it named one.
    refusal           text        CHECK (refusal ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    refusal_signature text        CHECK (length(refusal_signature) BETWEEN 1 AND 200),
    registered_at     timestamptz NOT NULL,
    received_at       timestamptz,
    CHECK ((state IN ('RECEIVED', 'PURGED')) = (version_id IS NOT NULL)),
    CHECK ((file_id IS NULL) = (version_id IS NULL)),
    CHECK ((version_id IS NULL) = (sha256 IS NULL)),
    CHECK ((state = 'REFUSED') = (refusal IS NOT NULL)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES imports_batch (tenant_id, id)
);
CREATE UNIQUE INDEX imports_file_path_uk ON imports_file (tenant_id, batch_id, path);
CREATE INDEX imports_file_fold_idx ON imports_file (tenant_id, batch_id, path_fold);
CREATE INDEX imports_file_state_idx ON imports_file (tenant_id, batch_id, state, path);

-- Every staged version a batch was given (sources and originals), so a replaced one is purged too (design E06 §4.3).
CREATE TABLE imports_staged (
    tenant_id     uuid        NOT NULL,
    version_id    uuid        NOT NULL,
    batch_id      uuid        NOT NULL,
    kind          text        NOT NULL CHECK (kind IN ('SOURCE', 'ORIGINAL')),
    staged_at     timestamptz NOT NULL,
    -- Set when a later upload took its place: its content is no longer used.
    superseded_at timestamptz,
    purged_at     timestamptz,
    PRIMARY KEY (tenant_id, version_id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES imports_batch (tenant_id, id)
);
CREATE INDEX imports_staged_batch_idx ON imports_staged (tenant_id, batch_id, purged_at, superseded_at);

-- A Memos row (design E06 §4.2), staged by the dry run.
CREATE TABLE imports_row (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    batch_id          uuid        NOT NULL,
    row_number        int         NOT NULL CHECK (row_number >= 1),
    legacy_number     text        CHECK (length(legacy_number) BETWEEN 1 AND 32767),
    legacy_number_key text        CHECK (length(legacy_number_key) BETWEEN 1 AND 32767),
    -- The canonical values as strings.
    data              jsonb       NOT NULL CHECK (jsonb_typeof(data) = 'object' AND octet_length(data::text) <= 262144),
    fingerprint       bytea       CHECK (length(fingerprint) = 32),
    result            text        NOT NULL DEFAULT 'PENDING' CHECK (result IN (
        'PENDING', 'READY', 'REJECTED', 'IMPORTING', 'IMPORTED', 'SKIPPED', 'FAILED', 'NOT_STARTED'
    )),
    memo_id           uuid,
    errors            int         NOT NULL DEFAULT 0 CHECK (errors >= 0),
    warnings          int         NOT NULL DEFAULT 0 CHECK (warnings >= 0),
    attempts          int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    updated_at        timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES imports_batch (tenant_id, id)
);
CREATE UNIQUE INDEX imports_row_number_uk ON imports_row (tenant_id, batch_id, row_number);
CREATE INDEX imports_row_key_idx ON imports_row (tenant_id, batch_id, legacy_number_key);
CREATE INDEX imports_row_result_idx ON imports_row (tenant_id, batch_id, result, row_number);

-- A Files row, matched to an original received.
CREATE TABLE imports_row_file (
    id                uuid    PRIMARY KEY,
    tenant_id         uuid    NOT NULL,
    batch_id          uuid    NOT NULL,
    row_number        int     NOT NULL CHECK (row_number >= 1),
    legacy_number_key text    CHECK (length(legacy_number_key) BETWEEN 1 AND 32767),
    path              text    CHECK (length(path) BETWEEN 1 AND 32767),
    role              text    CHECK (length(role) BETWEEN 1 AND 32767),
    title             text    CHECK (length(title) BETWEEN 1 AND 32767),
    expected_sha256   text    CHECK (length(expected_sha256) BETWEEN 1 AND 32767),
    classification    text    CHECK (length(classification) BETWEEN 1 AND 32767),
    row_id            uuid,
    file_id           uuid,
    match             text    CHECK (match IN ('EXACT', 'CASE', 'NONE', 'AMBIGUOUS')),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES imports_batch (tenant_id, id),
    FOREIGN KEY (tenant_id, row_id) REFERENCES imports_row (tenant_id, id),
    FOREIGN KEY (tenant_id, file_id) REFERENCES imports_file (tenant_id, id)
);
CREATE INDEX imports_row_file_key_idx ON imports_row_file (tenant_id, batch_id, legacy_number_key);
CREATE INDEX imports_row_file_row_idx ON imports_row_file (tenant_id, row_id);
CREATE INDEX imports_row_file_file_idx ON imports_row_file (tenant_id, file_id);

-- An Approvals row.
CREATE TABLE imports_row_approval (
    tenant_id         uuid NOT NULL,
    batch_id          uuid NOT NULL,
    row_number        int  NOT NULL CHECK (row_number >= 1),
    legacy_number_key text CHECK (length(legacy_number_key) BETWEEN 1 AND 32767),
    ordinal           text CHECK (length(ordinal) BETWEEN 1 AND 32767),
    name              text CHECK (length(name) BETWEEN 1 AND 32767),
    email             text CHECK (length(email) BETWEEN 1 AND 32767),
    legacy_id         text CHECK (length(legacy_id) BETWEEN 1 AND 32767),
    position          text CHECK (length(position) BETWEEN 1 AND 32767),
    decision          text CHECK (length(decision) BETWEEN 1 AND 32767),
    decided_on        text CHECK (length(decided_on) BETWEEN 1 AND 32767),
    remark            text CHECK (length(remark) BETWEEN 1 AND 32767),
    row_id            uuid,
    PRIMARY KEY (tenant_id, batch_id, row_number),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES imports_batch (tenant_id, id),
    FOREIGN KEY (tenant_id, row_id) REFERENCES imports_row (tenant_id, id)
);
CREATE INDEX imports_row_approval_row_idx ON imports_row_approval (tenant_id, row_id, ordinal);

-- A legacy value and what it maps to (design E06 §6).
CREATE TABLE imports_mapping (
    tenant_id    uuid        NOT NULL,
    batch_id     uuid        NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('STATUS', 'TYPE', 'CLASSIFICATION', 'UNIT', 'PERSON')),
    legacy_value text        NOT NULL CHECK (length(legacy_value) BETWEEN 1 AND 500),
    legacy_order int         CHECK (legacy_order >= 1),
    target       text        CHECK (length(target) BETWEEN 1 AND 100),
    proposed     boolean     NOT NULL DEFAULT false,
    confirmed_by uuid,
    confirmed_at timestamptz,
    row_count    int         NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL)),
    CHECK (legacy_order IS NULL OR kind = 'CLASSIFICATION'),
    PRIMARY KEY (tenant_id, batch_id, kind, legacy_value),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES imports_batch (tenant_id, id)
);

-- What a dry run, an import or the verification found (design E06 §9). Params hold codes, column names, values the
-- admin typed, counts and limits: never subjects or remarks.
CREATE TABLE imports_problem (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL,
    batch_id    uuid NOT NULL,
    stage       text NOT NULL CHECK (stage IN ('DRY_RUN', 'IMPORT', 'VERIFY')),
    level       text NOT NULL CHECK (level IN ('BATCH', 'ERROR', 'WARNING', 'FILE', 'INFO')),
    code        text NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    sheet       text CHECK (sheet IN ('MEMOS', 'FILES', 'APPROVALS', 'ORIGINALS', 'BATCH')),
    row_number  int  CHECK (row_number >= 1),
    column_name text CHECK (length(column_name) BETWEEN 1 AND 200),
    row_id      uuid,
    file_id     uuid,
    params      jsonb CHECK (jsonb_typeof(params) = 'object' AND octet_length(params::text) <= 2048),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES imports_batch (tenant_id, id),
    FOREIGN KEY (tenant_id, row_id) REFERENCES imports_row (tenant_id, id),
    FOREIGN KEY (tenant_id, file_id) REFERENCES imports_file (tenant_id, id)
);
CREATE INDEX imports_problem_list_idx ON imports_problem (tenant_id, batch_id, level, code, id);
CREATE INDEX imports_problem_row_idx ON imports_problem (tenant_id, row_id);
CREATE INDEX imports_problem_file_idx ON imports_problem (tenant_id, file_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'imports_batch', 'imports_file', 'imports_staged', 'imports_row', 'imports_row_file', 'imports_row_approval',
        'imports_mapping', 'imports_problem'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
        EXECUTE format('REVOKE TRUNCATE ON %I FROM memo_app', t);
    END LOOP;
END $$;
-- Batches, originals and staged versions are never deleted (AGENTS §3.3); staging rows are replaced by each dry run.
REVOKE DELETE ON imports_batch, imports_file, imports_staged FROM memo_app;
