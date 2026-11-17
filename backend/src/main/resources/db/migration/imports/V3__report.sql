-- Phase 3 E06 T8 (ADR-0055, design E06 §5.4, §8, §15): what verification, the report and closing record beyond V1 and
-- V2. Expand only: new nullable columns.

ALTER TABLE imports_batch
    -- When the verification pass ended, and what it counted (memos and originals checked, problems, fixity mismatches).
    ADD COLUMN verified_at  timestamptz,
    ADD COLUMN verification jsonb CHECK (jsonb_typeof(verification) = 'object' AND octet_length(verification::text) <= 4096),
    -- When every staged object of a CLOSED or ABANDONED batch was purged (the rows and their hashes stay).
    ADD COLUMN purged_at    timestamptz,
    ADD CONSTRAINT imports_batch_verified CHECK ((verified_at IS NULL) = (verification IS NULL)),
    ADD CONSTRAINT imports_batch_purged CHECK (purged_at IS NULL OR state IN ('CLOSED', 'ABANDONED'));

-- Closing marks each original received with a purged version PURGED, by version.
CREATE INDEX imports_file_version_idx ON imports_file (tenant_id, version_id);
