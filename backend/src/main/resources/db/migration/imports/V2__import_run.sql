-- Phase 3 E06 T7 (ADR-0055, design E06 §5.3): what the import job records beyond V1. Expand only: new nullable columns.

-- Since when the job has been waiting for the memo.legacy renditions to catch up (null: not waiting).
ALTER TABLE imports_batch ADD COLUMN throttled_at timestamptz;

-- The original as promoted (purpose memo.legacy, owner the row's reserved memo id): set once its copy is stored, so a row
-- that then fails leaves its stored originals listed ("stored, not bound", design E06 §8).
ALTER TABLE imports_row_file
    ADD COLUMN stored_file_id    uuid,
    ADD COLUMN stored_version_id uuid,
    ADD CONSTRAINT imports_row_file_stored CHECK ((stored_file_id IS NULL) = (stored_version_id IS NULL));
