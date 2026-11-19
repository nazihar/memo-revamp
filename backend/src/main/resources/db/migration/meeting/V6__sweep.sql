-- Phase 4 E03 T5 (design E03 §1.1, §9): the hourly meeting sweep's reminders, each sent once per meeting date.
--  - [agenda_due_reminded_at]: the paper deadline passed with the agenda not yet published, and the secretaries were told;
--  - [not_held_reminded_at]: the meeting was still SCHEDULED a day after it ended ("Not marked as held"), and the
--    secretaries were told.
-- Both are cleared when the meeting is rescheduled, so the new date reminds again. The sweep finds its meetings through
-- meeting_status_idx (tenant_id, status, ends_at). Expand only: two nullable columns. E04 T1 holds meeting/V5.
ALTER TABLE meeting
    ADD COLUMN agenda_due_reminded_at timestamptz,
    ADD COLUMN not_held_reminded_at   timestamptz;
