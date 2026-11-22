-- Phase 4 E06 T7 (ADR-0066, ADR-0053, design E06 §1.5, §9): the approved minutes kept as a PDF/A record, and the readers'
-- approved copies. Expand only: a new table, new nullable columns on `minutes_copy`, no data. Old app versions ignore them.
-- (T8's corrections and extracts become `minutes/V6`.)

-- One version of the approved minutes' record (ADR-0066): made by the `minutes-record` job from the approval it records
-- ([approval_id]: the chair's confirmation for version 1, T8's correction approvals after), the approved .docx version and
-- its SHA-256; PENDING until made, then READY (stored as a `minutes.record` file, object-locked: its file version, SHA-256,
-- size and pages, veraPDF's verdict and what went in otherwise than as text) or FAILED with a code (FAILED → PENDING to try
-- again). A READY version never changes. The earlier versions stay readable.
CREATE TABLE minutes_record (
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    meeting_id      uuid        NOT NULL,
    forum_id        uuid        NOT NULL,
    record_version  int         NOT NULL CHECK (record_version >= 1),
    approval_id     uuid        NOT NULL,
    docx_version_id uuid        NOT NULL,
    docx_sha256     text        NOT NULL CHECK (docx_sha256 ~ '^[0-9a-f]{64}$'),
    state           text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'READY', 'FAILED')),
    error           text        CHECK (char_length(error) BETWEEN 1 AND 100),
    file_id         uuid,
    file_version_id uuid,
    sha256          text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes      bigint      CHECK (size_bytes >= 0),
    pages           int         CHECK (pages >= 0),
    compliant       boolean,
    validation      jsonb       CHECK (validation IS NULL OR (jsonb_typeof(validation) = 'object' AND octet_length(validation::text) <= 16384)),
    created_at      timestamptz NOT NULL,
    built_at        timestamptz,
    version         bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((state = 'READY') = (file_version_id IS NOT NULL AND sha256 IS NOT NULL AND built_at IS NOT NULL AND compliant IS NOT NULL)),
    CHECK ((state = 'FAILED') = (error IS NOT NULL)),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES minutes (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, approval_id) REFERENCES minutes_approval (tenant_id, id)
);
-- The versions of one meeting's record, and one per number.
CREATE UNIQUE INDEX minutes_record_version_uk ON minutes_record (tenant_id, meeting_id, record_version);
-- The approval's foreign key.
CREATE INDEX minutes_record_approval_idx ON minutes_record (tenant_id, approval_id);
-- The weekly fixity run pages through the made versions in id order.
CREATE INDEX minutes_record_ready_idx ON minutes_record (tenant_id, id) WHERE state = 'READY';

ALTER TABLE minutes_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_record FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- A record version keeps its minutes, number, approval and source; it moves PENDING → READY or FAILED (and FAILED →
-- PENDING to try again); a READY one never changes; the version moves by one.
CREATE FUNCTION minutes_record_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.meeting_id <> OLD.meeting_id OR NEW.forum_id <> OLD.forum_id
        OR NEW.record_version <> OLD.record_version OR NEW.approval_id <> OLD.approval_id
        OR NEW.docx_version_id <> OLD.docx_version_id OR NEW.docx_sha256 <> OLD.docx_sha256 OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'record %: its minutes, version, approval and source never change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state = 'READY' OR (OLD.state = 'FAILED' AND NEW.state <> 'PENDING') OR (OLD.state = 'PENDING' AND NEW.state = 'PENDING') THEN
        RAISE EXCEPTION 'record %: % cannot become %', OLD.id, OLD.state, NEW.state USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'record %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_record_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_record_guard BEFORE UPDATE ON minutes_record FOR EACH ROW EXECUTE FUNCTION minutes_record_guard();

-- The approved copies (ADR-0066): a reader's copy of the approved minutes belongs to one version of the record
-- ([record_version], set for APPROVED copies only), is a PDF/A record of its own (`minutes.approved-copy`, object-locked)
-- with veraPDF's verdict ([compliant], [validation]). One copy per record version and set: the uniqueness of V4 gains the
-- record's version (a draft's copies have none, so they keep V4's rule).
ALTER TABLE minutes_copy
    ADD COLUMN record_version int     CHECK (record_version >= 1),
    ADD COLUMN compliant      boolean,
    ADD COLUMN validation     jsonb   CHECK (validation IS NULL OR (jsonb_typeof(validation) = 'object' AND octet_length(validation::text) <= 16384)),
    ADD CONSTRAINT minutes_copy_record_ck CHECK ((stage = 'APPROVED') = (record_version IS NOT NULL)),
    ADD CONSTRAINT minutes_copy_compliant_ck CHECK (stage = 'APPROVED' OR (compliant IS NULL AND validation IS NULL));
DROP INDEX minutes_copy_uk;
CREATE UNIQUE INDEX minutes_copy_uk ON minutes_copy (tenant_id, meeting_id, stage, source_version_id, set_key, coalesce(record_version, 0));
-- The weekly fixity run pages through the built approved copies in id order.
CREATE INDEX minutes_copy_approved_idx ON minutes_copy (tenant_id, id) WHERE stage = 'APPROVED' AND state = 'DONE';

-- The copy's guard (V4) also keeps its record version; a built copy's verdict never changes.
CREATE OR REPLACE FUNCTION minutes_copy_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.meeting_id <> OLD.meeting_id OR NEW.stage <> OLD.stage
        OR NEW.redacted <> OLD.redacted OR NEW.set_key <> OLD.set_key OR NEW.source_version_id <> OLD.source_version_id
        OR NEW.created_at <> OLD.created_at OR NEW.record_version IS DISTINCT FROM OLD.record_version THEN
        RAISE EXCEPTION 'copy %: its minutes, set and source never change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state = 'DONE' OR (OLD.state = 'FAILED' AND NEW.state <> 'PENDING') OR (OLD.state = 'PENDING' AND NEW.state = 'PENDING') THEN
        RAISE EXCEPTION 'copy %: % cannot become %', OLD.id, OLD.state, NEW.state USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'copy %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_copy_guard() FROM PUBLIC, memo_app;

-- The record is sacred (AGENTS.md §3.3): nothing is deleted; a record version changes only its build.
REVOKE UPDATE, DELETE, TRUNCATE ON minutes_record FROM memo_app;
GRANT UPDATE (state, error, file_id, file_version_id, sha256, size_bytes, pages, compliant, validation, built_at, version)
    ON minutes_record TO memo_app;
GRANT UPDATE (compliant, validation) ON minutes_copy TO memo_app;
