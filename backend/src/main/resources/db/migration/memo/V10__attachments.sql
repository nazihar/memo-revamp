-- Phase 3 E02 T4 (ADR-0046): attachments of a memo, each a file of purpose `memo.attachment` (its versions are the
-- attachment's versions), with an optional classification stricter than the memo's; and what each submission bound.
-- No foreign key to the files module's tables: each module's migrations stand alone (MemoTypeBackfillIT).
CREATE TABLE memo_attachment (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    memo_id             uuid        NOT NULL,
    file_id             uuid        NOT NULL,
    title               text        NOT NULL CHECK (length(title) BETWEEN 1 AND 255),
    -- Null: the memo's own level. Set: strictly above the memo's level when it was set (checked in the service).
    classification_id   uuid,
    classification_rank int         CHECK (classification_rank >= 0),
    position            int         NOT NULL CHECK (position >= 0),
    added_by            uuid        NOT NULL,
    added_at            timestamptz NOT NULL,
    removed_by          uuid,
    removed_at          timestamptz,
    version             bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, file_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id),
    CHECK ((classification_id IS NULL) = (classification_rank IS NULL)),
    CHECK ((removed_at IS NULL) = (removed_by IS NULL))
);
CREATE INDEX memo_attachment_present_idx ON memo_attachment (tenant_id, memo_id, position) WHERE removed_at IS NULL;

-- What a submission or resubmission bound (ADR-0046): one row per attachment present, with the version and its hash.
CREATE TABLE memo_submission_attachment (
    tenant_id       uuid  NOT NULL,
    memo_id         uuid  NOT NULL,
    round           int   NOT NULL CHECK (round >= 1),
    attachment_id   uuid  NOT NULL,
    file_version_id uuid  NOT NULL,
    sha256          bytea NOT NULL CHECK (length(sha256) = 32),
    position        int   NOT NULL CHECK (position >= 0),
    PRIMARY KEY (tenant_id, memo_id, round, attachment_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id),
    FOREIGN KEY (tenant_id, attachment_id) REFERENCES memo_attachment (tenant_id, id)
);

-- Attachments are removed by a flag, never deleted; a round's binding never changes.
REVOKE DELETE, TRUNCATE ON memo_attachment FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON memo_submission_attachment FROM memo_app;

ALTER TABLE memo_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE memo_attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memo_attachment FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE memo_submission_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE memo_submission_attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memo_submission_attachment FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));
