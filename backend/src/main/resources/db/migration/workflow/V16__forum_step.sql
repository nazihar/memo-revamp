-- Phase 4 E02 T1 (ADR-0058, design E02 §7): an item can wait at a forum step (type FORUM, no task) until the forum's
-- decision (FORUM_DECIDED) or refusal (FORUM_DECLINED) moves it on. Expand only: two CHECKs widened.
ALTER TABLE workflow_step DROP CONSTRAINT workflow_step_type_check;
ALTER TABLE workflow_step
    ADD CONSTRAINT workflow_step_type_check CHECK (type IN ('APPROVE', 'REVIEW', 'PARALLEL', 'FORUM'));

ALTER TABLE workflow_action DROP CONSTRAINT workflow_action_kind_check;
ALTER TABLE workflow_action
    ADD CONSTRAINT workflow_action_kind_check CHECK (kind IN (
        'SUBMIT', 'APPROVE', 'RETURN', 'REJECT', 'WITHDRAW', 'RESUBMIT', 'REASSIGN',
        'RECOMMEND', 'ROUTE', 'NOTIFY', 'ESCALATE_TO_FORUM', 'SEND_BACK', 'FORWARD', 'DELEGATE', 'ASK', 'ANSWER',
        'REFER', 'OPINION', 'RECALL', 'CANCEL', 'MIGRATE', 'CARRY', 'CLAIM', 'RELEASE', 'RECUSE', 'ESCALATE',
        'FORUM_DECIDED', 'FORUM_DECLINED'));
