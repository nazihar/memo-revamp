-- Platform tables for background jobs and the transactional outbox (ADR-0018, ADR-0019 §outbox).
-- No tenant_id and no RLS by design: they hold ids only (task data and event payloads), never tenant content.
-- Allow-listed in RlsMetaTest with these reasons.

-- db-scheduler 16.12.0 PostgreSQL schema, renamed to the module-prefixed table (db-scheduler.table-name).
CREATE TABLE system_scheduled_task (
    task_name            text        NOT NULL,
    task_instance        text        NOT NULL,
    task_data            bytea,
    execution_time       timestamptz NOT NULL,
    picked               boolean     NOT NULL,
    picked_by            text,
    last_success         timestamptz,
    last_failure         timestamptz,
    consecutive_failures int,
    last_heartbeat       timestamptz,
    version              bigint      NOT NULL,
    priority             smallint,
    PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX system_scheduled_task_execution_time_idx ON system_scheduled_task (execution_time);
CREATE INDEX system_scheduled_task_last_heartbeat_idx ON system_scheduled_task (last_heartbeat);
CREATE INDEX system_scheduled_task_priority_execution_time_idx ON system_scheduled_task (priority DESC, execution_time ASC);

-- Spring Modulith 2.1 event publication registry (table names are fixed by the framework).
CREATE TABLE event_publication (
    id                     uuid        NOT NULL PRIMARY KEY,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamptz
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

CREATE TABLE event_publication_archive (
    id                     uuid        NOT NULL PRIMARY KEY,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamptz
);
CREATE INDEX event_publication_archive_serialized_event_hash_idx ON event_publication_archive USING hash (serialized_event);
CREATE INDEX event_publication_archive_by_completion_date_idx ON event_publication_archive (completion_date);
