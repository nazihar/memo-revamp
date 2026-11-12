-- E03 T9 (design §1, §2): people a definition's notify step tells about a memo become its participants, so the link in
-- the notification opens it (clearance still applies).
ALTER TABLE memo_participant DROP CONSTRAINT memo_participant_kind_check;
ALTER TABLE memo_participant ADD CONSTRAINT memo_participant_kind_check CHECK (kind IN ('INITIATOR', 'APPROVER', 'NOTIFIED'));
