-- Mail outbox (design E03 §6): written in the business transaction, sent by a db-scheduler tenant task with retries.
-- Template parameters can hold one-time links, so they are encrypted (DataKeys) and removed once the mail is sent.
CREATE TABLE notification_mail_outbox (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    template   text        NOT NULL CHECK (length(template) <= 100),
    to_address text        NOT NULL CHECK (length(to_address) <= 320),
    params_enc bytea,
    status     text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts   int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at timestamptz NOT NULL,
    sent_at    timestamptz,
    UNIQUE (tenant_id, id)
);
CREATE INDEX notification_mail_outbox_tenant_status_idx ON notification_mail_outbox (tenant_id, status, created_at);

ALTER TABLE notification_mail_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_mail_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_mail_outbox FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
