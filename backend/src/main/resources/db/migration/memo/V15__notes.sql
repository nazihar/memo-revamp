-- Phase 3 E03 T3 (ADR-0049, design E03 §4): the note sheet. Entries are append-only: a decision's remark (linked to the
-- workflow action by its sequence in the instance), a comment, or a correction of an earlier entry. The body is the
-- noteDoc/v1 JSON as the server validated and stored it, with its plain text. Mentions and attachments are derived rows.
-- Drafts are private and mutable, one per person and memo, and not part of the record. Remarks made before E03 are not
-- copied: the note list merges them in from the workflow's actions.

CREATE TABLE memo_note (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    memo_id       uuid        NOT NULL,
    round         int         NOT NULL CHECK (round >= 1),
    kind          text        NOT NULL CHECK (kind IN ('REMARK', 'COMMENT', 'CORRECTION')),
    author_id     uuid        NOT NULL,
    acting_for_id uuid,
    -- A decision's remark: the action's sequence in the memo's workflow instance, and its kind.
    action_seq    bigint,
    action_kind   text,
    corrects_id   uuid,
    body          jsonb       NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 65536),
    body_schema   int         NOT NULL CHECK (body_schema = 1),
    body_text     text        NOT NULL CHECK (length(body_text) BETWEEN 1 AND 4000),
    created_at    timestamptz NOT NULL,
    CHECK ((kind = 'REMARK') = (action_seq IS NOT NULL)),
    CHECK ((kind = 'CORRECTION') = (corrects_id IS NOT NULL)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id),
    FOREIGN KEY (tenant_id, corrects_id) REFERENCES memo_note (tenant_id, id)
);
CREATE INDEX memo_note_tenant_memo_idx ON memo_note (tenant_id, memo_id, created_at);
CREATE INDEX memo_note_tenant_corrects_idx ON memo_note (tenant_id, corrects_id) WHERE corrects_id IS NOT NULL;
CREATE UNIQUE INDEX memo_note_one_remark_idx ON memo_note (tenant_id, memo_id, action_seq) WHERE action_seq IS NOT NULL;

CREATE TABLE memo_note_mention (
    tenant_id uuid NOT NULL,
    note_id   uuid NOT NULL,
    memo_id   uuid NOT NULL,
    user_id   uuid NOT NULL,
    PRIMARY KEY (tenant_id, note_id, user_id),
    FOREIGN KEY (tenant_id, note_id) REFERENCES memo_note (tenant_id, id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX memo_note_mention_tenant_memo_idx ON memo_note_mention (tenant_id, memo_id);
CREATE INDEX memo_note_mention_tenant_user_idx ON memo_note_mention (tenant_id, user_id);

CREATE TABLE memo_note_attachment (
    tenant_id uuid NOT NULL,
    note_id   uuid NOT NULL,
    memo_id   uuid NOT NULL,
    file_id   uuid NOT NULL,
    position  int  NOT NULL CHECK (position BETWEEN 0 AND 4),
    PRIMARY KEY (tenant_id, note_id, file_id),
    UNIQUE (tenant_id, file_id),
    FOREIGN KEY (tenant_id, note_id) REFERENCES memo_note (tenant_id, id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX memo_note_attachment_tenant_memo_idx ON memo_note_attachment (tenant_id, memo_id);

CREATE TABLE memo_note_draft (
    tenant_id  uuid        NOT NULL,
    memo_id    uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    body       jsonb       NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 65536),
    version    bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, memo_id, user_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['memo_note', 'memo_note_mention', 'memo_note_attachment', 'memo_note_draft'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;

-- The record (AGENTS §3.3): entries, mentions and bindings are written once and never changed or removed.
REVOKE UPDATE, DELETE, TRUNCATE ON memo_note FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON memo_note_mention FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON memo_note_attachment FROM memo_app;
REVOKE TRUNCATE ON memo_note_draft FROM memo_app;
