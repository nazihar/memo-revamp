-- Phase 4 E06 T8 (ADR-0066, ADR-0065, ADR-0051, design E06 §1.5, §1.6.5, §1.6.6, §6): corrections to approved minutes
-- and certified resolution extracts. Expand only: two new tables, a widened kind and two nullable columns on
-- `minutes_approval`, no data. Old app versions ignore them. (The plan's `minutes/V4`: T6 and T7 took V4 and V5.)

-- A correction to approved minutes (CGI §6.2): approved minutes are never edited; a correction is proposed by a secretary
-- ([item_id] the item it is about, or none for the minutes in general; [text] what is corrected, [reason] why), then
-- approved or declined once by the confirmer with a fresh step-up. An approval is an authenticated `minutes_approval` of
-- kind CORRECTION ([approval_id]) and gives the record a new version ([record_version]) with a "Corrections" annex; the
-- earlier versions stay locked and readable. [text], [reason] and [decision_note] are free text, never audited.
CREATE TABLE minutes_correction (
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    meeting_id     uuid        NOT NULL,
    forum_id       uuid        NOT NULL,
    item_id        uuid,
    text           text        NOT NULL CHECK (char_length(text) BETWEEN 1 AND 4000),
    reason         text        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    proposed_by    uuid        NOT NULL,
    proposed_at    timestamptz NOT NULL,
    state          text        NOT NULL DEFAULT 'PROPOSED' CHECK (state IN ('PROPOSED', 'APPROVED', 'DECLINED')),
    decided_by     uuid,
    decided_at     timestamptz,
    decision_note  text        CHECK (char_length(decision_note) BETWEEN 1 AND 1000),
    approval_id    uuid,
    record_version int         CHECK (record_version >= 2),
    version        bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((state = 'PROPOSED') = (decided_by IS NULL AND decided_at IS NULL)),
    CHECK ((state = 'APPROVED') = (approval_id IS NOT NULL AND record_version IS NOT NULL)),
    CHECK (state <> 'PROPOSED' OR decision_note IS NULL),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES minutes (tenant_id, meeting_id)
);
-- A meeting's corrections in time order (the minutes page, the record's annex).
CREATE INDEX minutes_correction_meeting_idx ON minutes_correction (tenant_id, meeting_id, proposed_at);
-- A committee's approved corrections since a date (`MinutesCorrections.since`, E07's report).
CREATE INDEX minutes_correction_forum_idx ON minutes_correction (tenant_id, forum_id, decided_at) WHERE state = 'APPROVED';
-- The approval's foreign key.
CREATE INDEX minutes_correction_approval_idx ON minutes_correction (tenant_id, approval_id) WHERE approval_id IS NOT NULL;

-- The minutes' authenticated approvals (V4) gain the chair's approval of a correction: the same ADR-0051 fields, chained
-- into the committee's chain, with the correction it approves and the SHA-256 of what it says ([correction_sha256]: the
-- item, text and reason as approved), both in the record hash. A confirmation has neither.
ALTER TABLE minutes_approval DROP CONSTRAINT minutes_approval_kind_check;
ALTER TABLE minutes_approval
    ADD CONSTRAINT minutes_approval_kind_check CHECK (kind IN ('CONFIRMATION', 'CORRECTION')),
    ADD COLUMN correction_id     uuid,
    ADD COLUMN correction_sha256 text CHECK (correction_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT minutes_approval_correction_ck
        CHECK ((kind = 'CORRECTION') = (correction_id IS NOT NULL AND correction_sha256 IS NOT NULL));
ALTER TABLE minutes_approval
    ADD CONSTRAINT minutes_approval_correction_fk FOREIGN KEY (tenant_id, correction_id) REFERENCES minutes_correction (tenant_id, id);
CREATE INDEX minutes_approval_correction_idx ON minutes_approval (tenant_id, correction_id) WHERE correction_id IS NOT NULL;
ALTER TABLE minutes_correction
    ADD CONSTRAINT minutes_correction_approval_fk FOREIGN KEY (tenant_id, approval_id) REFERENCES minutes_approval (tenant_id, id);

-- A certified extract of one resolution (design E06 §1.6.6): issued by a secretary of the committee not recused from the
-- item, with a fresh step-up ([certification]: ADR-0051's fields of the secretary's authentication and client;
-- [certification_hash] over them and what is certified), saying whether the minutes were approved when it was issued
-- ([minutes_state], [minutes_approved_at]). The `minutes-extract` job makes it as a PDF/A (`minutes.extract`, record
-- storage, object-locked): PENDING until made, then READY (its file, SHA-256, size, pages and veraPDF's verdict) or
-- FAILED with a code (FAILED → PENDING to try again). A READY extract never changes.
CREATE TABLE minutes_extract (
    id                  uuid        NOT NULL,
    tenant_id           uuid        NOT NULL,
    resolution_id       uuid        NOT NULL,
    meeting_id          uuid        NOT NULL,
    forum_id            uuid        NOT NULL,
    issued_by           uuid        NOT NULL,
    issued_at           timestamptz NOT NULL,
    minutes_state       text        NOT NULL CHECK (minutes_state IN ('NOT_APPROVED', 'APPROVED')),
    minutes_approved_at timestamptz,
    certification       jsonb       NOT NULL
                                    CHECK (jsonb_typeof(certification) = 'object' AND octet_length(certification::text) <= 8192),
    certification_hash  text        NOT NULL CHECK (certification_hash ~ '^[0-9a-f]{64}$'),
    state               text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'READY', 'FAILED')),
    error               text        CHECK (char_length(error) BETWEEN 1 AND 100),
    file_id             uuid,
    file_version_id     uuid,
    sha256              text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes          bigint      CHECK (size_bytes >= 0),
    pages               int         CHECK (pages >= 0),
    compliant           boolean,
    validation          jsonb       CHECK (validation IS NULL OR (jsonb_typeof(validation) = 'object' AND octet_length(validation::text) <= 16384)),
    built_at            timestamptz,
    version             bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((minutes_state = 'APPROVED') = (minutes_approved_at IS NOT NULL)),
    CHECK ((state = 'READY') = (file_version_id IS NOT NULL AND sha256 IS NOT NULL AND built_at IS NOT NULL AND compliant IS NOT NULL)),
    CHECK ((state = 'FAILED') = (error IS NOT NULL)),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, resolution_id) REFERENCES minutes_resolution (tenant_id, id)
);
-- A resolution's extracts (the foreign key; the resolution page lists them).
CREATE INDEX minutes_extract_resolution_idx ON minutes_extract (tenant_id, resolution_id, issued_at);
-- The weekly fixity run pages through the made extracts in id order.
CREATE INDEX minutes_extract_ready_idx ON minutes_extract (tenant_id, id) WHERE state = 'READY';

ALTER TABLE minutes_correction ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_correction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_correction FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE minutes_extract ENABLE ROW LEVEL SECURITY;
ALTER TABLE minutes_extract FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON minutes_extract FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- A correction keeps what was proposed; it is decided once (PROPOSED → APPROVED or DECLINED), never again; the version
-- moves by one.
CREATE FUNCTION minutes_correction_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.meeting_id <> OLD.meeting_id OR NEW.forum_id <> OLD.forum_id
        OR NEW.item_id IS DISTINCT FROM OLD.item_id OR NEW.text <> OLD.text OR NEW.reason <> OLD.reason
        OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_at <> OLD.proposed_at THEN
        RAISE EXCEPTION 'correction %: what was proposed never changes', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state <> 'PROPOSED' OR NEW.state = 'PROPOSED' THEN
        RAISE EXCEPTION 'correction %: decided once (% cannot become %)', OLD.id, OLD.state, NEW.state
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'correction %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_correction_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_correction_guard BEFORE UPDATE ON minutes_correction FOR EACH ROW EXECUTE FUNCTION minutes_correction_guard();

-- An extract keeps its resolution, issuer, certification and the minutes' state at issue; it moves PENDING → READY or
-- FAILED (and FAILED → PENDING to try again); a READY one never changes; the version moves by one.
CREATE FUNCTION minutes_extract_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.resolution_id <> OLD.resolution_id
        OR NEW.meeting_id <> OLD.meeting_id OR NEW.forum_id <> OLD.forum_id OR NEW.issued_by <> OLD.issued_by
        OR NEW.issued_at <> OLD.issued_at OR NEW.minutes_state <> OLD.minutes_state
        OR NEW.minutes_approved_at IS DISTINCT FROM OLD.minutes_approved_at OR NEW.certification <> OLD.certification
        OR NEW.certification_hash <> OLD.certification_hash THEN
        RAISE EXCEPTION 'extract %: what was certified never changes', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state = 'READY' OR (OLD.state = 'FAILED' AND NEW.state <> 'PENDING') OR (OLD.state = 'PENDING' AND NEW.state = 'PENDING') THEN
        RAISE EXCEPTION 'extract %: % cannot become %', OLD.id, OLD.state, NEW.state USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'extract %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_extract_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER minutes_extract_guard BEFORE UPDATE ON minutes_extract FOR EACH ROW EXECUTE FUNCTION minutes_extract_guard();

-- The record is sacred (AGENTS.md §3.3): nothing is deleted; a correction changes only its decision, an extract only its
-- build.
REVOKE UPDATE, DELETE, TRUNCATE ON minutes_correction, minutes_extract FROM memo_app;
GRANT UPDATE (state, decided_by, decided_at, decision_note, approval_id, record_version, version) ON minutes_correction TO memo_app;
GRANT UPDATE (state, error, file_id, file_version_id, sha256, size_bytes, pages, compliant, validation, built_at, version)
    ON minutes_extract TO memo_app;
