-- Phase 3 E06 T2 (ADR-0055, design E06 §3.3): staged copies of a legacy import are purged once the batch is closed. A
-- purge deletes an ordinary version's object from the `work` bucket and keeps its row, name and SHA-256, so the import's
-- report and audit still name what was received. Only versions of ORDINARY purposes declared `purgeable` are purged
-- (checked in code); record versions never are, here as in object storage. Expand only: both columns are nullable.
ALTER TABLE files_version
    ADD COLUMN purged_at timestamptz,
    ADD COLUMN purged_by uuid,
    ADD CONSTRAINT files_version_purged_work CHECK (purged_at IS NULL OR (bucket = 'work' AND lock_state = 'NONE')),
    ADD CONSTRAINT files_version_purged_by CHECK (purged_by IS NULL OR purged_at IS NOT NULL);

-- files_version stays append-only. Besides the lock moves of V1 and V3, the guard lets an ordinary version be marked
-- purged once, with nothing else changing.
CREATE OR REPLACE FUNCTION files_version_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.lock_state = 'PROVISIONAL' AND NEW.lock_state = 'FINAL'
       AND (to_jsonb(NEW) - 'lock_state') = (to_jsonb(OLD) - 'lock_state') THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE'
       AND OLD.retain_until IS NOT NULL AND NEW.retain_until > OLD.retain_until
       AND (to_jsonb(NEW) - 'retain_until') = (to_jsonb(OLD) - 'retain_until') THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE'
       AND OLD.bucket = 'work' AND OLD.lock_state = 'NONE'
       AND OLD.purged_at IS NULL AND NEW.purged_at IS NOT NULL
       AND (to_jsonb(NEW) - 'purged_at' - 'purged_by') = (to_jsonb(OLD) - 'purged_at' - 'purged_by') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'files_version is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;
REVOKE EXECUTE ON FUNCTION files_version_guard() FROM PUBLIC, memo_app;
