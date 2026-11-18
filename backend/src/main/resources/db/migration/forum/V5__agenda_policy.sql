-- Phase 4 E03 T1 (ADR-0060, design E03 §2, §7): what the meeting module needs from forums. Expand only.
--  - a committee type's agenda policy (template and settings), part of each type version and reviewed with it (T2 reads
--    and edits it); null reads as the defaults;
--  - when a committee's code was locked: at its first numbered meeting, after which the code never changes;
--  - where a submission is placed: the meeting and its date, so the memo's forum panel shows "On the agenda of the
--    meeting on 12 Dec" without knowing meetings (no foreign key: meetings are another module's rows, ADR-0007).
ALTER TABLE forum_type_version
    ADD COLUMN agenda jsonb CHECK (agenda IS NULL OR (jsonb_typeof(agenda) = 'object' AND octet_length(agenda::text) <= 65536));

ALTER TABLE forum ADD COLUMN code_locked_at timestamptz;

ALTER TABLE forum_submission
    ADD COLUMN meeting_id   uuid,
    ADD COLUMN meeting_date date,
    ADD CONSTRAINT forum_submission_placement_ck CHECK ((meeting_id IS NULL) = (meeting_date IS NULL));

-- The tray: a forum's open submissions not placed on an agenda.
CREATE INDEX forum_submission_tray_idx ON forum_submission (tenant_id, forum_id, status) WHERE meeting_id IS NULL;
-- The submissions placed on a meeting (cleared when it is cancelled).
CREATE INDEX forum_submission_meeting_idx ON forum_submission (tenant_id, meeting_id) WHERE meeting_id IS NOT NULL;
