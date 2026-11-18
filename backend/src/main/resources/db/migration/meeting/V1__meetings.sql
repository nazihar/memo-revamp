-- Phase 4 E03 T1 (ADR-0060, design E03 §1.1, §1.6, §6, §7): meetings of a forum, the per-forum and per-year number
-- counter, guests, and the materialised audience. No foreign keys to other modules' tables (each module migrates alone,
-- ADR-0007): forum, type, classification and user ids are checked by the services. Expand only: new tables.

CREATE TABLE meeting (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    forum_id           uuid        NOT NULL,
    -- The forum type version published when the meeting was created: its policies apply, whatever is published later.
    forum_type_version int         NOT NULL CHECK (forum_type_version >= 1),
    kind               text        NOT NULL CHECK (kind IN ('ORDINARY', 'SPECIAL')),
    title              text        CHECK (length(title) BETWEEN 1 AND 200),
    starts_at          timestamptz NOT NULL,
    ends_at            timestamptz NOT NULL,
    -- An IANA zone name: the times are entered and shown in it.
    time_zone          text        NOT NULL CHECK (length(time_zone) BETWEEN 1 AND 64),
    mode               text        NOT NULL CHECK (mode IN ('IN_PERSON', 'ONLINE', 'HYBRID')),
    location           text        CHECK (length(location) BETWEEN 1 AND 500),
    -- A plain https link, never called by the server.
    join_url           text        CHECK (length(join_url) <= 2000 AND join_url ~* '^https://'),
    join_details       text        CHECK (length(join_details) BETWEEN 1 AND 1000),
    classification_id  uuid        NOT NULL,
    -- Taken when the agenda is first published (T3): "{code} {number}/{number_year}".
    number             int         CHECK (number >= 1),
    number_year        int         CHECK (number_year BETWEEN 1900 AND 9999),
    label              text        CHECK (length(label) BETWEEN 1 AND 40),
    -- MINUTES_APPROVED and CLOSED are E06's.
    status             text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
        'DRAFT', 'SCHEDULED', 'IN_PROGRESS', 'HELD', 'MINUTES_APPROVED', 'CLOSED', 'CANCELLED')),
    status_reason      text        CHECK (length(status_reason) BETWEEN 1 AND 500),
    created_by         uuid,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at),
    CHECK (ends_at <= starts_at + interval '7 days'),
    CHECK (mode = 'ONLINE' OR location IS NOT NULL),
    CHECK (mode = 'IN_PERSON' OR join_url IS NOT NULL),
    CHECK ((number IS NULL) = (number_year IS NULL) AND (number IS NULL) = (label IS NULL)),
    CHECK ((status = 'CANCELLED') = (status_reason IS NOT NULL)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, forum_id, number_year, number)
);
-- A forum's meetings by date (the Meetings tab, the cursor); the sweeps by status and end (T5).
CREATE INDEX meeting_forum_idx ON meeting (tenant_id, forum_id, starts_at);
CREATE INDEX meeting_status_idx ON meeting (tenant_id, status, ends_at);

-- The last number taken per forum and calendar year, locked FOR UPDATE when a number is taken: no gaps (AGENTS.md §4).
CREATE TABLE meeting_counter (
    tenant_id uuid NOT NULL,
    forum_id  uuid NOT NULL,
    year      int  NOT NULL CHECK (year BETWEEN 1900 AND 9999),
    last      int  NOT NULL CHECK (last >= 0),
    PRIMARY KEY (tenant_id, forum_id, year)
);

-- People in attendance for the whole meeting who are not on the roster; a removal records its time, never a deletion.
CREATE TABLE meeting_guest (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    meeting_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    note       text        CHECK (length(note) BETWEEN 1 AND 200),
    added_by   uuid,
    added_at   timestamptz NOT NULL,
    removed_by uuid,
    removed_at timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id)
);
CREATE UNIQUE INDEX meeting_guest_current_uk ON meeting_guest (tenant_id, meeting_id, user_id) WHERE removed_at IS NULL;
CREATE INDEX meeting_guest_meeting_idx ON meeting_guest (tenant_id, meeting_id);

-- Who the meeting is for (design E03 §1.6), recomputed in the transaction of every change that affects it. A row that
-- no longer applies is marked removed (the app role deletes nothing); [until] is a member's end of office, compared with
-- today by the paper rule (ADR-0061), so nothing needs deleting when a term ends.
CREATE TABLE meeting_audience (
    tenant_id    uuid        NOT NULL,
    meeting_id   uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    source       text        NOT NULL CHECK (source IN ('MEMBER', 'GUEST', 'ATTENDEE')),
    members_only boolean     NOT NULL,
    until        date,
    removed_at   timestamptz,
    PRIMARY KEY (tenant_id, meeting_id, user_id, source),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id)
);
-- "My meetings": a user's current audience rows.
CREATE INDEX meeting_audience_user_idx ON meeting_audience (tenant_id, user_id, meeting_id) WHERE removed_at IS NULL;

ALTER TABLE meeting ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_counter FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_guest ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_guest FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_guest FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_audience ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_audience FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_audience FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Meetings are never deleted (design E03 §6): removal is a timestamp or a status.
REVOKE DELETE, TRUNCATE ON meeting, meeting_counter, meeting_guest, meeting_audience FROM memo_app;
