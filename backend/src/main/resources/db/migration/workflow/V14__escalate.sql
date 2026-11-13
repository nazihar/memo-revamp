-- E05 T5 (ADR-0041, design E05 §4.3): an overdue task escalated by the workflow is an ESCALATE action. Expand only.
ALTER TABLE workflow_action DROP CONSTRAINT workflow_action_kind_check;
ALTER TABLE workflow_action
    ADD CONSTRAINT workflow_action_kind_check CHECK (kind IN (
        'SUBMIT', 'APPROVE', 'RETURN', 'REJECT', 'WITHDRAW', 'RESUBMIT', 'REASSIGN',
        'RECOMMEND', 'ROUTE', 'NOTIFY', 'ESCALATE_TO_FORUM', 'SEND_BACK', 'FORWARD', 'DELEGATE', 'ASK', 'ANSWER',
        'REFER', 'OPINION', 'RECALL', 'CANCEL', 'MIGRATE', 'CARRY', 'CLAIM', 'RELEASE', 'RECUSE', 'ESCALATE'));
