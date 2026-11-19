-- Phase 4 E06 T2 (ADR-0065, ADR-0066, design E06 §1.8): a committee type's minutes policy, part of each type version and
-- reviewed with it like the rest (maker-checker): how minutes are approved (at the next meeting or by circulation) and
-- the comment and drafting windows, when a waiting memo moves on (at the meeting's end or on the minutes' approval),
-- the resolution number format, and an uploaded minutes template (else the built-in one). Null reads as the defaults.
-- Expand only: existing versions keep null, and old app versions ignore the column.
ALTER TABLE forum_type_version
    ADD COLUMN minutes jsonb CHECK (minutes IS NULL OR (jsonb_typeof(minutes) = 'object' AND octet_length(minutes::text) <= 8192));
