-- Edit sessions (ADR-0037, design E02 §2): who opened which file version in the browser editor, and how it ended. The
-- file and user are other modules' ids, checked by the services (no FKs across modules).

CREATE TABLE docedit_session (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    file_id          uuid        NOT NULL,
    base_version_id  uuid        NOT NULL,
    document_key     text        NOT NULL CHECK (document_key ~ '^[A-Za-z0-9._=-]{1,128}$'),
    mode             text        NOT NULL CHECK (mode IN ('EDIT', 'VIEW')),
    user_id          uuid        NOT NULL,
    state            text        NOT NULL CHECK (state IN ('OPEN', 'SAVED', 'CLOSED', 'FAILED', 'EXPIRED')),
    reason           text        CHECK (reason ~ '^[A-Z_]{1,40}$'),
    saved_version_id uuid,
    created_at       timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL CHECK (expires_at > created_at),
    version          bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK ((state = 'SAVED') = (saved_version_id IS NOT NULL) OR state = 'OPEN')
);
-- "Is anyone editing this file?" (submit is refused while so) and "my open session" (reuse instead of a second one).
CREATE INDEX docedit_session_tenant_file_idx ON docedit_session (tenant_id, file_id, state);
CREATE UNIQUE INDEX docedit_session_one_open_edit_idx ON docedit_session (tenant_id, file_id, user_id)
    WHERE state = 'OPEN' AND mode = 'EDIT';

ALTER TABLE docedit_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE docedit_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON docedit_session FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
