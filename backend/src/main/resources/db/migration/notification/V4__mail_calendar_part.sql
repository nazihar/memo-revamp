-- Phase 4 E05 T1 (ADR-0063, design E05 §2, §6, §7): a mail can carry an iCalendar part (a meeting invitation, update
-- or cancellation), sent as iMIP (RFC 6047): text/calendar with its method inside multipart/alternative, and the same
-- bytes as invite.ics. The file is encrypted like the parameters (DataKeys, bound to the tenant and the row) and cleared
-- with them once the mail is sent or has failed; the method stays, for the secretary's delivery view. Expand only: both
-- columns are null for every existing row and old app versions never read them.
ALTER TABLE notification_mail_outbox
    ADD COLUMN calendar_enc    bytea CHECK (octet_length(calendar_enc) <= 262144),
    ADD COLUMN calendar_method text  CHECK (calendar_method IN ('REQUEST', 'CANCEL')),
    ADD CONSTRAINT notification_mail_outbox_calendar_ck CHECK (calendar_enc IS NULL OR calendar_method IS NOT NULL);
