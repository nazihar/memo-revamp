-- Memos (design E01 §2): tenant-defined memo types, memos with their document and initiator-defined route, and who
-- takes part in each memo (for visibility filters in SQL). The approval itself lives in the workflow module's tables;
-- a memo knows only its instance id. No FKs to other modules' tables (modules migrate independently): user, unit,
-- classification, file and workflow ids are checked by the services.

CREATE TABLE memo_type (
    id                        uuid        PRIMARY KEY,
    tenant_id                 uuid        NOT NULL,
    -- Set for built-in types (`general`), so the seeding is idempotent; null for tenant-created types.
    key                       text        CHECK (key ~ '^[a-z][a-z0-9-]{0,49}$'),
    name                      text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    -- Null: the tenant's lowest classification level.
    default_classification_id uuid,
    route_policy              text        NOT NULL DEFAULT 'INITIATOR_DEFINED'
                                          CHECK (route_policy IN ('INITIATOR_DEFINED', 'FIXED', 'BOTH')),
    definition_key            text        NOT NULL DEFAULT 'sequential-approval' CHECK (definition_key ~ '^[a-z][a-z0-9-]{0,99}$'),
    step_up_on_approve        boolean     NOT NULL DEFAULT true,
    active                    boolean     NOT NULL DEFAULT true,
    version                   bigint      NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, key)
);
CREATE UNIQUE INDEX memo_type_tenant_name_uk ON memo_type (tenant_id, lower(name));

-- Per-tenant, per-year counter behind memo references (`M-2026-00042`); one row is locked while a memo is created.
CREATE TABLE memo_reference_counter (
    tenant_id   uuid NOT NULL,
    year        int  NOT NULL CHECK (year BETWEEN 2000 AND 9999),
    last_number int  NOT NULL CHECK (last_number >= 0),
    PRIMARY KEY (tenant_id, year)
);

CREATE TABLE memo (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    reference            text        NOT NULL CHECK (reference ~ '^M-[0-9]{4}-[0-9]{5,}$'),
    memo_type_id         uuid        NOT NULL,
    subject              text        NOT NULL CHECK (length(subject) BETWEEN 1 AND 300),
    classification_id    uuid        NOT NULL,
    -- Copied from the level so lists filter by clearance in SQL (design E01 §6).
    classification_rank  int         NOT NULL CHECK (classification_rank >= 0),
    initiator_id         uuid        NOT NULL,
    origin_unit_id       uuid,
    status               text        NOT NULL
                                     CHECK (status IN ('DRAFT', 'IN_REVIEW', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'DISCARDED')),
    -- The `memo.document` file; set by the first upload.
    document_file_id     uuid,
    -- Set at the first submit and kept through every round (invariant 1).
    workflow_instance_id uuid,
    current_round        int         NOT NULL DEFAULT 0 CHECK (current_round >= 0),
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reference),
    UNIQUE (tenant_id, document_file_id),
    FOREIGN KEY (tenant_id, memo_type_id) REFERENCES memo_type (tenant_id, id),
    CHECK ((status IN ('DRAFT', 'DISCARDED')) = (workflow_instance_id IS NULL)),
    CHECK ((workflow_instance_id IS NULL) = (current_round = 0))
);
-- Drafts, sent and closed boxes (design E01 §10): keyset on (updated_at desc, id).
CREATE INDEX memo_tenant_initiator_box_idx ON memo (tenant_id, initiator_id, status, updated_at DESC, id);
CREATE INDEX memo_tenant_type_idx ON memo (tenant_id, memo_type_id);

-- The initiator's ordered approvers; replaced as a whole by `PUT /memos/{id}/route` (DRAFT and RETURNED only).
CREATE TABLE memo_route_entry (
    tenant_id   uuid NOT NULL,
    memo_id     uuid NOT NULL,
    ordinal     int  NOT NULL CHECK (ordinal >= 1),
    approver_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, memo_id, ordinal),
    UNIQUE (tenant_id, memo_id, approver_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);

-- Who takes part in a memo: the initiator from creation, approvers from their first task (design E01 §6). Visibility
-- is participation and clearance ≥ the memo's classification rank.
CREATE TABLE memo_participant (
    tenant_id  uuid        NOT NULL,
    memo_id    uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('INITIATOR', 'APPROVER')),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, memo_id, user_id),
    FOREIGN KEY (tenant_id, memo_id) REFERENCES memo (tenant_id, id)
);
CREATE INDEX memo_participant_tenant_user_idx ON memo_participant (tenant_id, user_id, memo_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['memo_type', 'memo_reference_counter', 'memo', 'memo_route_entry', 'memo_participant'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I FOR ALL
            USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
            WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))$p$, t);
    END LOOP;
END $$;
