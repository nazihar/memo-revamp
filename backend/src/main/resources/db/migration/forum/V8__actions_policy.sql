-- Phase 4 E07 T2 (ADR-0067, design E07 §1.13): a committee type's actions policy, part of each type version and reviewed
-- with it like the rest (maker-checker): when an action turns amber, when it counts as having no recent update, who agrees
-- a new due date, who verifies closure, whether evidence is required, the default due date, when owners are asked for an
-- update before the papers, and the workflow definition actions run on. Null reads as the defaults. Unlike the minutes
-- policy it is read from the version in force today, not the meeting's pinned one. Expand only: existing versions keep
-- null, and old app versions ignore the column.
ALTER TABLE forum_type_version
    ADD COLUMN actions jsonb CHECK (actions IS NULL OR (jsonb_typeof(actions) = 'object' AND octet_length(actions::text) <= 4096));
