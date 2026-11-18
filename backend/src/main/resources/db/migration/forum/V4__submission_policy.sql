-- Phase 4 E02 T3 (ADR-0059, design E02 §1): a committee type's submission policy, part of each type version (maker-checker
-- like the rest). Null reads as the defaults: papers due 5 working days before a meeting, late papers need the chair's
-- approval, and the secretaries are reminded of a paper left untriaged for 5 working days. Expand only.
ALTER TABLE forum_type_version
    ADD COLUMN submission_policy jsonb CHECK (submission_policy IS NULL OR jsonb_typeof(submission_policy) = 'object');

-- The waiting reminder's sweep: queued submissions by age.
CREATE INDEX forum_submission_waiting_idx ON forum_submission (tenant_id, submitted_at)
    WHERE status = 'QUEUED' AND reminded_at IS NULL;
