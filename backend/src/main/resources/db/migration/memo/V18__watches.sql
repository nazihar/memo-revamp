-- Phase 3 E05 T4 (design E05 §2, §4; flow J): following memos. A watch is one person following one memo, with why
-- (followed by hand, or automatically as initiator, actor or person mentioned). What happens to a followed memo is kept
-- as watch events until the person is told in the app (after a quiet spell, batched per memo) and by email (a daily
-- digest, at once, or never: their watch settings).

CREATE TABLE memo_watch (
    tenant_id  uuid        NOT NULL,
    memo_id    uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    reason     text        NOT NULL CHECK (reason IN ('MANUAL', 'INITIATED', 'ACTED', 'MENTIONED')),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, memo_id, user_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
-- The Watching tab: a person's watches, newest first.
CREATE INDEX memo_watch_user_idx ON memo_watch (tenant_id, user_id, created_at DESC, memo_id);

CREATE TABLE memo_watch_event (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    memo_id     uuid        NOT NULL,
    -- The person following the memo; never the person who acted (no one is told of their own action).
    user_id     uuid        NOT NULL,
    kind        text        NOT NULL CHECK (kind IN ('STEP_DECIDED', 'NOTE_ADDED', 'CLOSED', 'RECORD_READY')),
    created_at  timestamptz NOT NULL,
    notified_at timestamptz,
    emailed_at  timestamptz,
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX memo_watch_event_memo_idx ON memo_watch_event (tenant_id, memo_id);
CREATE INDEX memo_watch_event_unnotified_idx ON memo_watch_event (tenant_id, user_id, memo_id) WHERE notified_at IS NULL;
CREATE INDEX memo_watch_event_unemailed_idx ON memo_watch_event (tenant_id, user_id) WHERE emailed_at IS NULL;

-- A person's choices (flow J): follow memos they initiate or act on (on by default), and how followed memos are emailed.
CREATE TABLE memo_watch_setting (
    tenant_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    auto_watch boolean     NOT NULL DEFAULT true,
    email      text        NOT NULL DEFAULT 'DIGEST' CHECK (email IN ('DIGEST', 'IMMEDIATE', 'OFF')),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['memo_watch', 'memo_watch_event', 'memo_watch_setting'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;

