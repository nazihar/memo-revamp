-- Phase 4 E05 T1 (ADR-0063, ADR-0064, design E05 §1.8): a committee type's meeting policy, part of each type version
-- and reviewed with it like the rest (maker-checker): the invitation's title (the name, or a neutral title), the reply
-- deadline and reminders, and whether a decision may be recorded without a quorum. Null reads as the defaults. Expand
-- only: existing versions keep null, and old app versions ignore the column.
ALTER TABLE forum_type_version
    ADD COLUMN meetings jsonb CHECK (meetings IS NULL OR (jsonb_typeof(meetings) = 'object' AND octet_length(meetings::text) <= 4096));
