-- Phase 4 E05 T5 (ADR-0064, design E05 §1.7, §6, §7): the quorum snapshot each decision takes (E06), append-only. The
-- table was planned in V9 and moved here (T4). Expand only: a new table, no change to existing ones, no data.

-- What the quorum was when a decision was recorded: the inputs (the meeting, the item, the instant, the pinned type
-- version's rule, the roster in office on the meeting's date, who filled which seat, who was recused from the item and
-- who had stepped out of it) and the result, with the SHA-256 of the inputs' canonical JSON (the audit module's form).
-- Never changed: a later attendance correction is compared with it (drift), never written into it. A snapshot that is
-- not quorate exists only with an override: its reason (the type allows it, ADR-0064), by [taken_by], to be ratified at
-- a quorate meeting.
CREATE TABLE meeting_quorum_snapshot (
    id                    uuid        NOT NULL,
    tenant_id             uuid        NOT NULL,
    meeting_id            uuid        NOT NULL,
    -- The item decided; null for a decision of the meeting as a whole.
    item_id               uuid,
    at                    timestamptz NOT NULL,
    type_id               uuid        NOT NULL,
    type_version          int         NOT NULL CHECK (type_version >= 1),
    inputs                jsonb       NOT NULL CHECK (jsonb_typeof(inputs) = 'object'),
    result                jsonb       NOT NULL CHECK (jsonb_typeof(result) = 'object'),
    quorate               boolean     NOT NULL,
    override_reason       text        CHECK (char_length(override_reason) BETWEEN 1 AND 500),
    ratification_required boolean     NOT NULL,
    inputs_sha256         text        NOT NULL CHECK (inputs_sha256 ~ '^[0-9a-f]{64}$'),
    taken_by              uuid,
    taken_at              timestamptz NOT NULL,
    CHECK (quorate OR override_reason IS NOT NULL),
    CHECK (override_reason IS NULL OR NOT quorate),
    CHECK (ratification_required = (override_reason IS NOT NULL)),
    CHECK (override_reason IS NULL OR taken_by IS NOT NULL),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id)
);
-- A meeting's snapshots by item and time (E06's decisions, the minutes).
CREATE INDEX meeting_quorum_snapshot_idx ON meeting_quorum_snapshot (tenant_id, meeting_id, item_id, at);

ALTER TABLE meeting_quorum_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_quorum_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_quorum_snapshot FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Append-only (design E05 §6): never updated, never deleted.
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_quorum_snapshot FROM memo_app;
