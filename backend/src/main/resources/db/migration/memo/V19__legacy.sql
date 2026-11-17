-- Phase 3 E06 T3 (ADR-0055, design E06 §3.1): memos imported from a legacy system. An imported memo is in a final state
-- from the start and never enters a workflow; its legacy number is its reference, outside every number series (so outside
-- the register and its gap check); its initiator may be unknown here (a person is linked only by an explicit identifier).
-- What the legacy system said is kept as given in memo_legacy and beside it, append-only: approvals as history, the
-- original files, and later corrections as new rows. Expand only: existing memos are NATIVE and keep every rule.

ALTER TABLE memo
    ADD COLUMN origin text NOT NULL DEFAULT 'NATIVE' CONSTRAINT memo_origin_check CHECK (origin IN ('NATIVE', 'IMPORTED')),
    ALTER COLUMN initiator_id DROP NOT NULL,
    ADD CONSTRAINT memo_initiator_native CHECK (origin = 'IMPORTED' OR initiator_id IS NOT NULL);

-- V1 left "a workflow exactly when past DRAFT" unnamed; it is found by its definition and replaced, for native memos only.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO STRICT c FROM pg_constraint
    WHERE conrelid = 'memo'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%DISCARDED%'
      AND pg_get_constraintdef(oid) LIKE '%workflow_instance_id IS NULL%';
    EXECUTE format('ALTER TABLE memo DROP CONSTRAINT %I', c);
END $$;

ALTER TABLE memo
    ADD CONSTRAINT memo_workflow_by_status CHECK (
        origin = 'IMPORTED' OR ((status IN ('DRAFT', 'DISCARDED')) = (workflow_instance_id IS NULL))
    ),
    -- Invariant 1: final, numbered outside any series, no workflow, document, approval time; pinned to a type version.
    ADD CONSTRAINT memo_imported_shape CHECK (
        origin = 'NATIVE' OR (
            status IN ('APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED', 'CLOSED')
            AND workflow_instance_id IS NULL
            AND current_round = 0
            AND reference IS NOT NULL
            AND number_series IS NULL
            AND document_file_id IS NULL
            AND approved_at IS NULL
            AND memo_type_version_id IS NOT NULL
        )
    );
-- V9's memo_closed_at still holds: an imported CLOSED memo has closed_at (the start of its legacy closing date).

-- Where imported memos are counted or listed by when they were imported.
CREATE INDEX memo_tenant_imported_idx ON memo (tenant_id, created_at) WHERE origin = 'IMPORTED';

-- Invariant 2: the origin never changes, and an imported memo changes only by a correction of its subject or a raise of
-- its classification (with the version and change time that go with them). Every other column, today's and later ones,
-- stays as imported. memo_number_guard and memo_pinned_guard keep the reference, type and metadata as well.
CREATE FUNCTION memo_imported_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
DECLARE
    mutable CONSTANT text[] := ARRAY['subject', 'classification_id', 'classification_rank', 'updated_at', 'version'];
BEGIN
    IF NEW.origin IS DISTINCT FROM OLD.origin THEN
        RAISE EXCEPTION 'memo % keeps its origin', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.origin = 'IMPORTED' AND (
        (to_jsonb(NEW) - mutable) IS DISTINCT FROM (to_jsonb(OLD) - mutable)
        OR NEW.classification_rank < OLD.classification_rank
    ) THEN
        RAISE EXCEPTION 'memo % was imported; only its subject and a higher classification can change', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION memo_imported_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER memo_imported_guard BEFORE UPDATE ON memo FOR EACH ROW EXECUTE FUNCTION memo_imported_guard();

-- The legacy facts of an imported memo, as given (one row per memo). The row key (source, normalised legacy number) is
-- unique in the tenant, so a row is imported at most once across batches (ADR-0055). batch_id and the user ids are
-- other modules' ids, checked by the services (no foreign keys across modules, ADR-0007).
CREATE TABLE memo_legacy (
    tenant_id           uuid        NOT NULL,
    memo_id             uuid        NOT NULL,
    batch_id            uuid        NOT NULL,
    row_number          int         NOT NULL CHECK (row_number >= 1),
    source_code         text        NOT NULL CHECK (source_code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    legacy_number       text        NOT NULL CHECK (length(legacy_number) BETWEEN 1 AND 100),
    -- Trimmed, inner spaces collapsed, NFC, upper case (LegacyNumbers.key).
    legacy_number_key   text        NOT NULL CHECK (length(legacy_number_key) BETWEEN 1 AND 100),
    row_fingerprint     bytea       NOT NULL CHECK (length(row_fingerprint) = 32),
    -- The batch's IANA time zone, in which the legacy dates are days.
    time_zone           text        NOT NULL CHECK (length(time_zone) BETWEEN 1 AND 64),
    raised_on           date        NOT NULL,
    decided_on          date,
    closed_on           date,
    legacy_status       text        NOT NULL CHECK (length(legacy_status) BETWEEN 1 AND 200),
    legacy_type         text        NOT NULL CHECK (length(legacy_type) BETWEEN 1 AND 200),
    legacy_classification text      CHECK (length(legacy_classification) BETWEEN 1 AND 200),
    legacy_unit         text        NOT NULL CHECK (length(legacy_unit) BETWEEN 1 AND 200),
    subject_as_given    text        NOT NULL CHECK (length(subject_as_given) BETWEEN 1 AND 300),
    initiator_name      text        NOT NULL CHECK (length(initiator_name) BETWEEN 1 AND 200),
    initiator_email     text        CHECK (length(initiator_email) BETWEEN 1 AND 320),
    initiator_legacy_id text        CHECK (length(initiator_legacy_id) BETWEEN 1 AND 100),
    -- Null unless linked by an explicit identifier confirmed in the batch's people mapping (invariant 3).
    initiator_user_id   uuid,
    imported_at         timestamptz NOT NULL,
    -- Who started the batch; never written as a person of the memo.
    imported_by         uuid        NOT NULL,
    CHECK (decided_on IS NULL OR decided_on >= raised_on),
    CHECK (closed_on IS NULL OR closed_on >= coalesce(decided_on, raised_on)),
    PRIMARY KEY (tenant_id, memo_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
-- Invariant 5: the row key.
CREATE UNIQUE INDEX memo_legacy_key_uk ON memo_legacy (tenant_id, source_code, legacy_number_key);
CREATE INDEX memo_legacy_batch_idx ON memo_legacy (tenant_id, batch_id);

-- The legacy approvals, as history: never workflow actions, tasks or stamps (ADR-0055).
CREATE TABLE memo_legacy_approval (
    tenant_id     uuid NOT NULL,
    memo_id       uuid NOT NULL,
    ordinal       int  NOT NULL CHECK (ordinal BETWEEN 1 AND 50),
    name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    email         text CHECK (length(email) BETWEEN 1 AND 320),
    legacy_id     text CHECK (length(legacy_id) BETWEEN 1 AND 100),
    user_id       uuid,
    position_text text CHECK (length(position_text) BETWEEN 1 AND 200),
    decision      text NOT NULL CHECK (length(decision) BETWEEN 1 AND 100),
    decided_on    date,
    remark        text CHECK (length(remark) BETWEEN 1 AND 4000),
    PRIMARY KEY (tenant_id, memo_id, ordinal),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo_legacy (tenant_id, memo_id)
);

-- The originals (purpose memo.legacy): the record of an imported memo, with the hash and size they were imported with.
CREATE TABLE memo_legacy_file (
    id                  uuid   PRIMARY KEY,
    tenant_id           uuid   NOT NULL,
    memo_id             uuid   NOT NULL,
    file_id             uuid   NOT NULL,
    role                text   NOT NULL CHECK (role IN ('MAIN', 'ATTACHMENT')),
    position            int    NOT NULL CHECK (position BETWEEN 0 AND 50),
    title               text   NOT NULL CHECK (length(title) BETWEEN 1 AND 255),
    relative_path       text   NOT NULL CHECK (length(relative_path) BETWEEN 1 AND 1024),
    -- Null: the memo's level. Set: strictly above the memo's level at import (checked in the service).
    classification_id   uuid,
    classification_rank int    CHECK (classification_rank >= 0),
    sha256              bytea  NOT NULL CHECK (length(sha256) = 32),
    size_bytes          bigint NOT NULL CHECK (size_bytes > 0),
    CHECK ((classification_id IS NULL) = (classification_rank IS NULL)),
    UNIQUE (tenant_id, file_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo_legacy (tenant_id, memo_id)
);
-- Invariant 4: exactly one MAIN per memo (at least one is checked in the service).
CREATE UNIQUE INDEX memo_legacy_file_main_uk ON memo_legacy_file (tenant_id, memo_id) WHERE role = 'MAIN';
CREATE UNIQUE INDEX memo_legacy_file_memo_idx ON memo_legacy_file (tenant_id, memo_id, position);

-- Corrections after import (design E06 §11, E06 T4): appended with a reason; the legacy facts stay as given.
CREATE TABLE memo_legacy_correction (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    memo_id      uuid        NOT NULL,
    field        text        NOT NULL CHECK (field IN (
        'SUBJECT', 'RAISED_ON', 'DECIDED_ON', 'CLOSED_ON', 'INITIATOR_NAME', 'LEGACY_UNIT', 'LEGACY_TYPE', 'CLASSIFICATION'
    )),
    value_before text        CHECK (length(value_before) <= 300),
    value_after  text        CHECK (length(value_after) <= 300),
    reason       text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    corrected_by uuid        NOT NULL,
    corrected_at timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo_legacy (tenant_id, memo_id)
);
CREATE INDEX memo_legacy_correction_memo_idx ON memo_legacy_correction (tenant_id, memo_id, corrected_at);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['memo_legacy', 'memo_legacy_approval', 'memo_legacy_file', 'memo_legacy_correction'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
        -- Append-only (AGENTS §3.3): the record as imported, and corrections as new rows.
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM memo_app', t);
    END LOOP;
END $$;
