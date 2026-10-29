-- Spring Session JDBC store (ADR-0023), renamed into the identity module. Platform tables: sessions are looked up by
-- their id before any tenant is known to the store; the principal name `<tenantId>:<userId>` and the session principal
-- carry the tenant, which MemoSecurityContextRepository checks against the request host on every request.
-- Session ids are 256-bit random strings (not UUIDs), hence text columns.

CREATE TABLE identity_http_session (
    primary_id            text   PRIMARY KEY CHECK (length(primary_id) <= 64),
    session_id            text   NOT NULL CHECK (length(session_id) <= 64),
    creation_time         bigint NOT NULL,
    last_access_time      bigint NOT NULL,
    max_inactive_interval int    NOT NULL,
    expiry_time           bigint NOT NULL,
    principal_name        text   CHECK (length(principal_name) <= 100)
);
CREATE UNIQUE INDEX identity_http_session_id_uk ON identity_http_session (session_id);
CREATE INDEX identity_http_session_expiry_idx ON identity_http_session (expiry_time);
CREATE INDEX identity_http_session_principal_idx ON identity_http_session (principal_name);

CREATE TABLE identity_http_session_attributes (
    session_primary_id text  NOT NULL REFERENCES identity_http_session (primary_id) ON DELETE CASCADE,
    attribute_name     text  NOT NULL CHECK (length(attribute_name) <= 200),
    attribute_bytes    bytea NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);
