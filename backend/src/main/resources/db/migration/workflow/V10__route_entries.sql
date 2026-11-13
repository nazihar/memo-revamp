-- E04 T7 (design E04 §4.7): an initiator's route may name positions and groups as well as people. `route` keeps the
-- person of each entry (null for the others); `route_entries` holds the whole route when it has any other entry.
ALTER TABLE workflow_round
    ADD COLUMN route_entries jsonb CHECK (
        route_entries IS NULL OR (jsonb_typeof(route_entries) = 'array' AND octet_length(route_entries::text) <= 65536)
    );
