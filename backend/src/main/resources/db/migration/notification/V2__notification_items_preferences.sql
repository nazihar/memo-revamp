-- In-app notifications and preferences (ADR-0030). Items are many and short-lived: monthly partitions on created_at,
-- created ahead and dropped after the retention by the migrator's afterMigrate callback (NotificationPartitionCallback),
-- so the app role needs no DDL and never deletes. The only change to an item is marking it read.

CREATE TABLE notification_item (
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    kind       text        NOT NULL CHECK (kind ~ '^[A-Z][A-Z_]{1,59}$'),
    category   text        NOT NULL CHECK (category ~ '^[A-Z][A-Z_]{1,39}$'),
    params     jsonb       NOT NULL DEFAULT '{}' CHECK (octet_length(params::text) <= 4096),
    link       text        CHECK (link ~ '^/[A-Za-z0-9/_.~%?=&-]{0,499}$'),
    created_at timestamptz NOT NULL,
    read_at    timestamptz,
    PRIMARY KEY (tenant_id, id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX notification_item_user_idx ON notification_item (tenant_id, user_id, created_at DESC, id DESC);
CREATE INDEX notification_item_unread_idx ON notification_item (tenant_id, user_id) WHERE read_at IS NULL;

ALTER TABLE notification_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_item FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
REVOKE DELETE, TRUNCATE ON notification_item FROM memo_app;

-- Monthly partitions (UTC), each with the parent's RLS and grants.
CREATE FUNCTION notification_secure_partition(part text) RETURNS void
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', part);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', part);
    EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
        USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
        WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, part);
    EXECUTE format('REVOKE DELETE, TRUNCATE ON %I FROM memo_app', part);
END $$;

CREATE FUNCTION notification_ensure_partitions(months_ahead int, retention_months int) RETURNS int
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
DECLARE
    month_start date;
    part        text;
    changed     int := 0;
    old         record;
    cutoff      text := 'notification_item_y' || to_char(date_trunc('month', now() AT TIME ZONE 'UTC')
                                                           - make_interval(months => retention_months), 'YYYY"m"MM');
BEGIN
    FOR i IN 0..months_ahead LOOP
        month_start := (date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => i))::date;
        part := format('notification_item_y%sm%s', to_char(month_start, 'YYYY'), to_char(month_start, 'MM'));
        IF to_regclass(part) IS NULL THEN
            EXECUTE format('CREATE TABLE %I PARTITION OF notification_item FOR VALUES FROM (%L) TO (%L)', part,
                           to_char(month_start, 'YYYY-MM-DD') || ' 00:00:00+00',
                           to_char(month_start + interval '1 month', 'YYYY-MM-DD') || ' 00:00:00+00');
            PERFORM notification_secure_partition(part);
            changed := changed + 1;
        END IF;
    END LOOP;
    -- Retention: monthly partitions older than retention_months are dropped whole (names sort by month).
    FOR old IN
        SELECT c.relname FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = 'notification_item' AND c.relname ~ '^notification_item_y[0-9]{4}m[0-9]{2}$' AND c.relname < cutoff
    LOOP
        EXECUTE format('DROP TABLE %I', old.relname);
        changed := changed + 1;
    END LOOP;
    RETURN changed;
END $$;

REVOKE EXECUTE ON FUNCTION notification_ensure_partitions(int, int) FROM PUBLIC, memo_app;
REVOKE EXECUTE ON FUNCTION notification_secure_partition(text) FROM PUBLIC, memo_app;

-- Safety net for rows outside every monthly partition (the callback creates months well ahead).
CREATE TABLE notification_item_default PARTITION OF notification_item DEFAULT;
SELECT notification_secure_partition('notification_item_default');

-- Per user, category and channel; a missing row means enabled. Mandatory categories are enforced by the service.
CREATE TABLE notification_preference (
    tenant_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    category   text        NOT NULL CHECK (category ~ '^[A-Z][A-Z_]{1,39}$'),
    channel    text        NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL', 'PUSH')),
    enabled    boolean     NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, user_id, category, channel)
);
ALTER TABLE notification_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_preference FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Per-tenant SMTP relay (ADR-0030); the password is encrypted (DataKeys, bound to the tenant) and write-only.
CREATE TABLE notification_smtp (
    tenant_id    uuid        PRIMARY KEY,
    host         text        NOT NULL CHECK (host ~ '^[A-Za-z0-9.-]{1,253}$'),
    port         int         NOT NULL CHECK (port BETWEEN 1 AND 65535),
    tls_mode     text        NOT NULL CHECK (tls_mode IN ('STARTTLS', 'TLS', 'NONE')),
    username     text        CHECK (length(username) BETWEEN 1 AND 320),
    password_enc bytea       CHECK (length(password_enc) <= 2048),
    from_address text        NOT NULL CHECK (length(from_address) BETWEEN 3 AND 320),
    from_name    text        CHECK (length(from_name) BETWEEN 1 AND 100),
    version      bigint      NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL
);
ALTER TABLE notification_smtp ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_smtp FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_smtp FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Delivery log on the outbox (ADR-0030): locale for templates, relay used, last SMTP code and error class.
ALTER TABLE notification_mail_outbox
    ADD COLUMN locale          text        CHECK (length(locale) <= 35),
    ADD COLUMN relay           text        CHECK (relay IN ('TENANT', 'PLATFORM')),
    ADD COLUMN smtp_code       int         CHECK (smtp_code BETWEEN 100 AND 599),
    ADD COLUMN error_class     text        CHECK (length(error_class) <= 100),
    ADD COLUMN last_attempt_at timestamptz,
    ADD COLUMN failed_at       timestamptz;
CREATE INDEX notification_mail_outbox_tenant_created_idx ON notification_mail_outbox (tenant_id, created_at DESC, id DESC);
