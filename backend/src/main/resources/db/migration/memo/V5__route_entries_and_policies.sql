-- E04 T7 (design E04 §3, §4.7): route entries naming a position or a group (people and positions deciding together),
-- and memo type policies: who may be on a route (empty: anyone) and whether the route must satisfy the DoA. Expand only.
ALTER TABLE memo_route_entry
    ALTER COLUMN approver_id DROP NOT NULL,
    ADD COLUMN position_id uuid,
    ADD COLUMN group_entry jsonb CHECK (
        group_entry IS NULL OR (jsonb_typeof(group_entry) = 'object' AND octet_length(group_entry::text) <= 8192)
    ),
    ADD CONSTRAINT memo_route_entry_kind_check CHECK (num_nonnulls(approver_id, position_id, group_entry) = 1);

ALTER TABLE memo_type
    ADD COLUMN allowed_approvers jsonb NOT NULL DEFAULT '{}' CHECK (
        jsonb_typeof(allowed_approvers) = 'object' AND octet_length(allowed_approvers::text) <= 16384
    ),
    ADD COLUMN doa_check boolean NOT NULL DEFAULT false;
