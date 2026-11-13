-- E03 (design §4.4): an administrator can cancel a memo's workflow; the memo then shows as cancelled.
ALTER TABLE memo DROP CONSTRAINT memo_status_check;
ALTER TABLE memo ADD CONSTRAINT memo_status_check
    CHECK (status IN ('DRAFT', 'IN_REVIEW', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'DISCARDED', 'CANCELLED'));
