-- Fix after Phase 4 E08 T10 (2026-11-28): a version of the approved minutes' record, or a reader's approved copy, that
-- failed on its job's last try was never asked for again (a longer document server outage left approved minutes without
-- their record). The minutes' part of the meeting sweep now asks for a failed one again once a day with backoff, and a
-- secretary not recused can ask at once ("Make the record again"). Each row keeps when it last failed ([failed_at]) and
-- how many times it was asked for again ([retries], counted up only); the secretaries are told once per record version
-- (`minutes_reminder` kind RECORD_FAILED). Expand only: new columns with defaults, two partial indexes, a reminder kind;
-- old app versions ignore them. A row FAILED before this release has no [failed_at]: the sweep counts from its creation.
ALTER TABLE minutes_record
    ADD COLUMN failed_at timestamptz,
    ADD COLUMN retries   int NOT NULL DEFAULT 0 CHECK (retries >= 0);
ALTER TABLE minutes_copy
    ADD COLUMN failed_at timestamptz,
    ADD COLUMN retries   int NOT NULL DEFAULT 0 CHECK (retries >= 0);

-- The sweep finds the failed ones (few, usually none) without reading the made ones.
CREATE INDEX minutes_record_failed_idx ON minutes_record (tenant_id, id) WHERE state = 'FAILED';
CREATE INDEX minutes_copy_failed_idx ON minutes_copy (tenant_id, id) WHERE stage = 'APPROVED' AND state = 'FAILED';

GRANT UPDATE (failed_at, retries) ON minutes_record TO memo_app;
GRANT UPDATE (failed_at, retries) ON minutes_copy TO memo_app;

ALTER TABLE minutes_reminder DROP CONSTRAINT minutes_reminder_kind_check;
ALTER TABLE minutes_reminder ADD CONSTRAINT minutes_reminder_kind_check
    CHECK (kind IN ('COMMENTS_CLOSING', 'DRAFT_OVERDUE', 'CONFIRMATION', 'RECORD_FAILED'));

-- The guards (V5) also keep the count of retries from going down.
CREATE OR REPLACE FUNCTION minutes_record_guard() RETURNS trigger
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
    IF NEW.retries < OLD.retries THEN
        RAISE EXCEPTION 'record %: its retries are only counted up', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'record %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_record_guard() FROM PUBLIC, memo_app;

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
    IF NEW.retries < OLD.retries THEN
        RAISE EXCEPTION 'copy %: its retries are only counted up', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'copy %: the version moves by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION minutes_copy_guard() FROM PUBLIC, memo_app;
