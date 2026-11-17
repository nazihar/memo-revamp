-- Phase 3 E04 T5 (ADR-0053, design E04 §6): a record's lock is extended when its memo closes (retention counted from
-- closure). files_version stays append-only; the guard now also lets retain_until move later, and nothing else change
-- with it. Shortening stays impossible here as it is in object storage.

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
    RAISE EXCEPTION 'files_version is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;
REVOKE EXECUTE ON FUNCTION files_version_guard() FROM PUBLIC, memo_app;
