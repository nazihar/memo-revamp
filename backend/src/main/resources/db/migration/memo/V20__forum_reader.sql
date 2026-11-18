-- Phase 4 E02 T3 (ADR-0059, design E02 §2): while a memo waits in a committee's queue, the committee's chair and
-- secretaries read it through a participant row of the new kind FORUM; the row is retired when no open submission of the
-- memo names them. Expand only: existing rows keep their kind.
ALTER TABLE memo_participant DROP CONSTRAINT memo_participant_kind_check;
ALTER TABLE memo_participant
    ADD CONSTRAINT memo_participant_kind_check
        CHECK (kind IN ('INITIATOR', 'APPROVER', 'NOTIFIED', 'COAUTHOR', 'COPY', 'SHARED', 'FORUM'));
