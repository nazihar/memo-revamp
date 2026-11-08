-- Platform operators (ADR-0031): not tenant users. A platform table without tenant_id, visible and writable only in
-- platform scope (app.scope = 'platform', set by PlatformTransactions); tenant transactions see no rows.
-- TOTP is always required: secret_enc is null until the first enrolment is confirmed. The one-time enrolment link
-- (bootstrap command, 24 hours) is stored as its SHA-256 only.

CREATE TABLE identity_operator (
    id                   uuid        PRIMARY KEY,
    email                text        NOT NULL UNIQUE CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 320),
    display_name         text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    status               text        NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    password_hash        text        CHECK (length(password_hash) <= 500),
    totp_secret_enc      bytea,
    totp_pending_enc     bytea,
    totp_last_step       bigint      NOT NULL DEFAULT 0,
    enrolment_token_hash bytea       CHECK (length(enrolment_token_hash) = 32),
    enrolment_expires_at timestamptz,
    failed_logins        int         NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
    locked_until         timestamptz,
    last_login_at        timestamptz,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CHECK ((enrolment_token_hash IS NULL) = (enrolment_expires_at IS NULL))
);
CREATE UNIQUE INDEX identity_operator_enrolment_uk ON identity_operator (enrolment_token_hash);

ALTER TABLE identity_operator ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_operator FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_operator ON identity_operator FOR ALL
    USING      ((select current_setting('app.scope', true)) = 'platform')
    WITH CHECK ((select current_setting('app.scope', true)) = 'platform');
