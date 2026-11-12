-- E04 T4 (design E04 §4.4): a holder can declare an interest and stand aside (SOD-4); the history records it. Expand only.
ALTER TABLE workflow_action DROP CONSTRAINT workflow_action_kind_check;
ALTER TABLE workflow_action
    ADD CONSTRAINT workflow_action_kind_check CHECK (kind IN (
        'SUBMIT', 'APPROVE', 'RETURN', 'REJECT', 'WITHDRAW', 'RESUBMIT', 'REASSIGN',
        'RECOMMEND', 'ROUTE', 'NOTIFY', 'ESCALATE_TO_FORUM', 'SEND_BACK', 'FORWARD', 'DELEGATE', 'ASK', 'ANSWER',
        'REFER', 'OPINION', 'RECALL', 'CANCEL', 'MIGRATE', 'CARRY', 'CLAIM', 'RELEASE', 'RECUSE'));
