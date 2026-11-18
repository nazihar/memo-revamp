-- Phase 4 E02 T2 (ADR-0059, design E02 §1, §7): a submission per escalation of a workflow item to a forum, in the
-- forum's queue until it is decided, returned, declined, referred or withdrawn. Expand only: a new table.

CREATE TABLE forum_submission (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    forum_id           uuid        NOT NULL,
    subject_type       text        NOT NULL CHECK (subject_type ~ '^[a-z][a-z0-9.-]{0,99}$'),
    subject_id         uuid        NOT NULL,
    instance_id        uuid        NOT NULL,
    -- The step the item waits at (ADR-0058); null for a pass-through ("for information") escalation.
    step_id            uuid,
    previous_id        uuid,
    purpose            text        NOT NULL CHECK (purpose IN ('DECISION', 'DISCUSSION', 'INFORMATION')),
    resolution_sought  text        CHECK (length(resolution_sought) BETWEEN 1 AND 2000),
    presenter_id       uuid,
    status             text        NOT NULL CHECK (status IN (
        'QUEUED', 'ACCEPTED', 'DEFERRED', 'DECIDED', 'RETURNED', 'DECLINED', 'WITHDRAWN', 'REFERRED')),
    target_date        date,
    submitted_by       uuid        NOT NULL,
    submitted_at       timestamptz NOT NULL,
    triaged_by         uuid,
    triaged_at         timestamptz,
    reason             text        CHECK (length(reason) BETWEEN 1 AND 2000),
    outcome            text        CHECK (outcome IN ('APPROVED', 'APPROVED_WITH_CONDITIONS', 'NOTED', 'REJECTED', 'RETURNED')),
    decided_by         uuid,
    decided_at         timestamptz,
    reminded_at        timestamptz,
    version            bigint      NOT NULL DEFAULT 0,
    CHECK ((status = 'DECIDED') = (outcome IS NOT NULL)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, forum_id) REFERENCES forum (tenant_id, id),
    FOREIGN KEY (tenant_id, previous_id) REFERENCES forum_submission (tenant_id, id)
);
-- One open submission per waiting step: a replayed escalation cannot queue the item twice; a referral closes the old
-- submission (REFERRED) before the new forum's one opens with the same step.
CREATE UNIQUE INDEX forum_submission_step_uk ON forum_submission (tenant_id, step_id)
    WHERE step_id IS NOT NULL AND status IN ('QUEUED', 'ACCEPTED', 'DEFERRED');
CREATE INDEX forum_submission_queue_idx ON forum_submission (tenant_id, forum_id, status, submitted_at);
CREATE INDEX forum_submission_subject_idx ON forum_submission (tenant_id, subject_type, subject_id);
CREATE INDEX forum_submission_instance_idx ON forum_submission (tenant_id, instance_id);
CREATE INDEX forum_submission_previous_idx ON forum_submission (tenant_id, previous_id) WHERE previous_id IS NOT NULL;

ALTER TABLE forum_submission ENABLE ROW LEVEL SECURITY;
ALTER TABLE forum_submission FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON forum_submission FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Submissions are never deleted (ADR-0059).
REVOKE DELETE, TRUNCATE ON forum_submission FROM memo_app;
