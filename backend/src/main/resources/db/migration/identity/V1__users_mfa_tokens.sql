-- Local accounts, TOTP MFA, recovery codes and single-use action tokens (ADR-0023, ADR-0024). One account per tenant
-- (ADR-0022): email is unique per tenant, not globally.

CREATE TABLE identity_user (
    id            uuid        PRIMARY KEY,
    -- No FK to tenancy_tenant: modules migrate independently, and tenants are never deleted (offboarding shreds keys).
    tenant_id     uuid        NOT NULL,
    email         text        NOT NULL CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 320),
    display_name  text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    status        text        NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'LOCKED', 'DEACTIVATED')),
    password_hash text        CHECK (length(password_hash) <= 500),
    -- Replaced by role grants in E04 (expand → migrate → contract).
    tenant_admin  boolean     NOT NULL DEFAULT false,
    failed_logins int         NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
    locked_until  timestamptz,
    last_login_at timestamptz,
    locale        text        CHECK (length(locale) <= 35),
    version       bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX identity_user_tenant_email_uk ON identity_user (tenant_id, email);
CREATE INDEX identity_user_tenant_name_idx ON identity_user (tenant_id, lower(display_name), id);

-- TOTP (ADR-0024). Secrets are AES-GCM encrypted (DataKeys, bound to tenant and user). `secret_enc` is the active
-- secret (null until the first enrolment is confirmed); `pending_secret_enc` an enrolment in progress, so re-enrolling
-- never drops a working secret before the new one is confirmed.
CREATE TABLE identity_mfa (
    user_id            uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    secret_enc         bytea,
    pending_secret_enc bytea,
    last_step          bigint      NOT NULL DEFAULT 0,
    enrolled_at        timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CHECK (secret_enc IS NOT NULL OR pending_secret_enc IS NOT NULL),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity_user (tenant_id, id)
);
CREATE INDEX identity_mfa_tenant_user_idx ON identity_mfa (tenant_id, user_id);

CREATE TABLE identity_recovery_code (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    code_hash  text        NOT NULL CHECK (length(code_hash) <= 500),
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity_user (tenant_id, id)
);
CREATE INDEX identity_recovery_code_tenant_user_idx ON identity_recovery_code (tenant_id, user_id);

CREATE TABLE identity_action_token (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    purpose    text        NOT NULL CHECK (purpose IN ('INVITATION', 'PASSWORD_RESET')),
    token_hash bytea       NOT NULL CHECK (length(token_hash) = 32),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity_user (tenant_id, id)
);
CREATE UNIQUE INDEX identity_action_token_tenant_hash_uk ON identity_action_token (tenant_id, token_hash);
CREATE INDEX identity_action_token_tenant_user_idx ON identity_action_token (tenant_id, user_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['identity_user', 'identity_mfa', 'identity_recovery_code', 'identity_action_token'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;
