-- Phase 4 E01 T2 (ADR-0056, design E01 §1, §7): forums (concrete committees of a type) and their members, one row per
-- term of office; term reminders sent (T3 job), kept so the job is idempotent. Expand only: new tables.

CREATE TABLE forum (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    key               text        NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,99}$'),
    code              text        NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,9}$'),
    name              text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    short_name        text        NOT NULL CHECK (length(short_name) BETWEEN 1 AND 40),
    forum_type_id     uuid        NOT NULL,
    parent_forum_id   uuid,
    classification_id uuid,
    status            text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISSOLVED')),
    dissolved_on      date,
    dissolved_reason  text        CHECK (length(dissolved_reason) BETWEEN 1 AND 500),
    created_by        uuid,
    created_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CHECK ((status = 'DISSOLVED') = (dissolved_on IS NOT NULL)),
    CHECK (parent_forum_id IS NULL OR parent_forum_id <> id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, forum_type_id) REFERENCES forum_type (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_forum_id) REFERENCES forum (tenant_id, id)
);
CREATE INDEX forum_type_idx ON forum (tenant_id, forum_type_id);
CREATE INDEX forum_parent_idx ON forum (tenant_id, parent_forum_id);
CREATE INDEX forum_status_idx ON forum (tenant_id, status);

CREATE TABLE forum_member (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    forum_id          uuid        NOT NULL,
    kind              text        NOT NULL CHECK (kind IN ('PERSON', 'POSITION')),
    user_id           uuid,
    position_id       uuid,
    role              text        NOT NULL CHECK (role IN ('CHAIR', 'VICE_CHAIR', 'SECRETARY', 'MEMBER', 'STANDING_INVITEE', 'OBSERVER')),
    voting            boolean     NOT NULL,
    attributes        jsonb       NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(attributes) = 'array' AND octet_length(attributes::text) <= 1024),
    alternate_user_id uuid,
    starts_on         date        NOT NULL,
    term_ends_on      date,
    ended_on          date,
    end_reason        text        CHECK (end_reason IN ('RESIGNED', 'TERM_COMPLETED', 'REMOVED', 'RENEWED', 'ROLE_CHANGED', 'WITHDRAWN', 'OTHER')),
    end_note          text        CHECK (length(end_note) BETWEEN 1 AND 500),
    term_number       int         NOT NULL DEFAULT 1 CHECK (term_number BETWEEN 1 AND 99),
    authority         text        CHECK (length(authority) BETWEEN 1 AND 500),
    previous_id       uuid,
    created_by        uuid,
    created_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CHECK ((kind = 'PERSON') = (user_id IS NOT NULL AND position_id IS NULL)),
    CHECK ((kind = 'POSITION') = (position_id IS NOT NULL AND user_id IS NULL)),
    CHECK (alternate_user_id IS NULL OR alternate_user_id IS DISTINCT FROM user_id),
    -- A withdrawn upcoming row ends the day before it would have started.
    CHECK (ended_on IS NULL OR ended_on >= starts_on - 1),
    CHECK (term_ends_on IS NULL OR term_ends_on >= starts_on),
    CHECK ((ended_on IS NULL) = (end_reason IS NULL)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, forum_id) REFERENCES forum (tenant_id, id),
    FOREIGN KEY (tenant_id, previous_id) REFERENCES forum_member (tenant_id, id)
);
CREATE INDEX forum_member_forum_idx ON forum_member (tenant_id, forum_id, starts_on);
CREATE INDEX forum_member_user_idx ON forum_member (tenant_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX forum_member_position_idx ON forum_member (tenant_id, position_id) WHERE position_id IS NOT NULL;
CREATE INDEX forum_member_alternate_idx ON forum_member (tenant_id, alternate_user_id) WHERE alternate_user_id IS NOT NULL;
CREATE INDEX forum_member_previous_idx ON forum_member (tenant_id, previous_id) WHERE previous_id IS NOT NULL;
CREATE INDEX forum_member_term_idx ON forum_member (tenant_id, term_ends_on) WHERE ended_on IS NULL;

CREATE TABLE forum_term_reminder (
    tenant_id   uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    days_before int         NOT NULL CHECK (days_before BETWEEN 1 AND 365),
    sent_at     timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, member_id, days_before),
    FOREIGN KEY (tenant_id, member_id) REFERENCES forum_member (tenant_id, id)
);

ALTER TABLE forum ENABLE ROW LEVEL SECURITY;
ALTER TABLE forum FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON forum FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE forum_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE forum_member FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON forum_member FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE forum_term_reminder ENABLE ROW LEVEL SECURITY;
ALTER TABLE forum_term_reminder FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON forum_term_reminder FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Forums and terms of office are never deleted (ADR-0056); reminders are append-only.
REVOKE DELETE, TRUNCATE ON forum, forum_member FROM memo_app;
REVOKE UPDATE, DELETE, TRUNCATE ON forum_term_reminder FROM memo_app;
