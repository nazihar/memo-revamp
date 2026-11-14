-- Phase 3 E02 T5 (ADR-0047): co-authors of a draft are memo participants, so the memo's read rules include them; they
-- may edit the document and the attachments while the memo is editable, and stay readers after submit.
ALTER TABLE memo_participant DROP CONSTRAINT memo_participant_kind_check;
ALTER TABLE memo_participant ADD CONSTRAINT memo_participant_kind_check
    CHECK (kind IN ('INITIATOR', 'APPROVER', 'NOTIFIED', 'COAUTHOR'));
