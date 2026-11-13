-- E03 (ADR-0038, ADR-0039, design E03 §3): tenant-authored definitions with a review lifecycle, steps activated as an
-- instance walks its definition, consultation tasks, and the new actions. Expand only: new columns are nullable or
-- defaulted, CHECK lists are widened, and E01 rows are backfilled to what they already mean.

-- Definitions: the compiled body the runtime reads, and who submitted and approved each version (maker-checker).
ALTER TABLE workflow_definition
    ADD COLUMN description text CHECK (length(description) <= 2000),
    ADD COLUMN fragment boolean NOT NULL DEFAULT false;

ALTER TABLE workflow_definition_version
    ADD COLUMN compiled      jsonb CHECK (octet_length(compiled::text) <= 2097152),
    ADD COLUMN submitted_by  uuid,
    ADD COLUMN submitted_at  timestamptz,
    ADD COLUMN reviewed_by   uuid,
    ADD COLUMN reviewed_at   timestamptz,
    ADD COLUMN review_remark text CHECK (length(review_remark) BETWEEN 1 AND 4000),
    ADD COLUMN published_at  timestamptz,
    ADD CONSTRAINT workflow_definition_version_checker_check CHECK (reviewed_by IS NULL OR reviewed_by <> submitted_by);
-- One published version per definition at a time; publishing retires the previous one in the same transaction.
CREATE UNIQUE INDEX workflow_definition_version_one_published_idx
    ON workflow_definition_version (tenant_id, definition_id) WHERE state = 'PUBLISHED';

-- Instances: cancellation, and the active step's name for "who holds it, at which step".
ALTER TABLE workflow_instance DROP CONSTRAINT workflow_instance_status_check;
ALTER TABLE workflow_instance
    ADD CONSTRAINT workflow_instance_status_check
        CHECK (status IN ('RUNNING', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED')),
    ADD COLUMN current_step_name text CHECK (length(current_step_name) <= 200);

-- Rounds: recall and cancellation; the initiator's route for the round (E01 kept it only as the steps).
ALTER TABLE workflow_round DROP CONSTRAINT workflow_round_outcome_check;
ALTER TABLE workflow_round
    ADD CONSTRAINT workflow_round_outcome_check
        CHECK (outcome IN ('OPEN', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'RECALLED', 'CANCELLED')),
    ADD COLUMN route uuid[] NOT NULL DEFAULT '{}';
UPDATE workflow_round r
SET route = (SELECT array_agg(s.assignee_id ORDER BY s.ordinal) FROM workflow_step s WHERE s.round_id = r.id)
WHERE EXISTS (SELECT 1 FROM workflow_step s WHERE s.round_id = r.id);

-- Steps: which definition step each one activates; route entries keep their position in the route.
ALTER TABLE workflow_step DROP CONSTRAINT workflow_step_type_check;
ALTER TABLE workflow_step
    ADD CONSTRAINT workflow_step_type_check CHECK (type IN ('APPROVE', 'REVIEW', 'PARALLEL')),
    ADD COLUMN node_id     text NOT NULL DEFAULT 'route' CHECK (node_id ~ '^[a-z][a-z0-9/-]{0,199}$'),
    ADD COLUMN route_index int CHECK (route_index >= 0);
UPDATE workflow_step SET route_index = ordinal - 1;
ALTER TABLE workflow_step ALTER COLUMN node_id DROP DEFAULT;

-- Tasks: the decision task of a step, or a consultation (opinion, clarification) attached to one.
ALTER TABLE workflow_task DROP CONSTRAINT workflow_task_state_check;
ALTER TABLE workflow_task DROP CONSTRAINT workflow_task_outcome_check;
ALTER TABLE workflow_task
    ADD CONSTRAINT workflow_task_state_check CHECK (state IN ('OPEN', 'COMPLETED', 'CANCELLED', 'REASSIGNED', 'TRANSFERRED')),
    ADD CONSTRAINT workflow_task_outcome_check
        CHECK (outcome IN ('APPROVE', 'RECOMMEND', 'RETURN', 'REJECT', 'SEND_BACK', 'ANSWER', 'OPINION', 'CARRY')),
    ADD COLUMN kind           text    NOT NULL DEFAULT 'DECIDE' CHECK (kind IN ('DECIDE', 'OPINION', 'CLARIFY')),
    ADD COLUMN on_behalf_of   uuid,
    ADD COLUMN waiting        boolean NOT NULL DEFAULT false,
    ADD COLUMN parent_task_id uuid,
    ADD CONSTRAINT workflow_task_parent_check CHECK ((kind = 'DECIDE') = (parent_task_id IS NULL));
-- Parallel steps hold several decision tasks; one person still holds at most one open decision per step.
DROP INDEX workflow_task_one_open_idx;
CREATE UNIQUE INDEX workflow_task_one_open_idx ON workflow_task (tenant_id, step_id, assignee_id)
    WHERE state = 'OPEN' AND kind = 'DECIDE';
CREATE INDEX workflow_task_parent_idx ON workflow_task (tenant_id, parent_task_id) WHERE parent_task_id IS NOT NULL;

-- Actions: everything E03 adds, and what an automatic action was based on (condition inputs, recipients, targets).
ALTER TABLE workflow_action DROP CONSTRAINT workflow_action_kind_check;
ALTER TABLE workflow_action
    ADD CONSTRAINT workflow_action_kind_check CHECK (kind IN (
        'SUBMIT', 'APPROVE', 'RETURN', 'REJECT', 'WITHDRAW', 'RESUBMIT', 'REASSIGN',
        'RECOMMEND', 'ROUTE', 'NOTIFY', 'ESCALATE_TO_FORUM', 'SEND_BACK', 'FORWARD', 'DELEGATE', 'ASK', 'ANSWER',
        'REFER', 'OPINION', 'RECALL', 'CANCEL', 'MIGRATE', 'CARRY')),
    ADD COLUMN details jsonb NOT NULL DEFAULT '{}' CHECK (octet_length(details::text) <= 4096);
