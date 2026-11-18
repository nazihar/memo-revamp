-- Phase 4 E03 T2 (ADR-0060, ADR-0061, design E03 §1.2, §1.7, §1.8, §6, §7): a meeting's working draft agenda, its
-- sections, items (one level of sub-items), per-item attendees and papers. Published revisions are T3's (meeting/V3).
-- No foreign keys to other modules' tables (ADR-0007): submissions, memos, files, classifications and users are checked
-- by the services. Nothing is deleted: removal records its time (and, for items, why). Expand only: new tables.

-- One agenda per meeting, started from the type version's template or from an earlier meeting.
CREATE TABLE meeting_agenda (
    meeting_id        uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    state             text        NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'PUBLISHED')),
    started_from      text        NOT NULL CHECK (started_from IN ('TEMPLATE', 'MEETING')),
    source_meeting_id uuid,
    -- The chair's agreement to the first publication (T3): {by, at, onBehalfBy?, note?}.
    chair_agreement   jsonb       CHECK (chair_agreement IS NULL OR (jsonb_typeof(chair_agreement) = 'object'
                                         AND octet_length(chair_agreement::text) <= 4096)),
    -- The last published revision (T3); 0 before the first.
    current_revision  int         NOT NULL DEFAULT 0 CHECK (current_revision >= 0),
    -- Changes since the last revision.
    dirty             boolean     NOT NULL DEFAULT false,
    created_by        uuid,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    -- Every builder change carries it as If-Match and runs under this row's lock.
    version           bigint      NOT NULL DEFAULT 0,
    CHECK ((started_from = 'MEETING') = (source_meeting_id IS NOT NULL)),
    UNIQUE (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id),
    FOREIGN KEY (tenant_id, source_meeting_id) REFERENCES meeting (tenant_id, id)
);
CREATE INDEX meeting_agenda_source_idx ON meeting_agenda (tenant_id, source_meeting_id) WHERE source_meeting_id IS NOT NULL;

-- Unnumbered headings; at most one receives the submissions placed from the tray.
CREATE TABLE meeting_section (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    meeting_id           uuid        NOT NULL,
    position             int         NOT NULL CHECK (position >= 0),
    title                text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    kind                 text        NOT NULL CHECK (kind IN ('STANDARD', 'CONSENT')),
    -- In camera: for the chair, vice-chair, members and secretaries only (ADR-0061).
    members_only         boolean     NOT NULL DEFAULT false,
    receives_submissions boolean     NOT NULL DEFAULT false,
    created_at           timestamptz NOT NULL,
    removed_by           uuid,
    removed_at           timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_agenda (tenant_id, meeting_id)
);
CREATE INDEX meeting_section_meeting_idx ON meeting_section (tenant_id, meeting_id, position);
CREATE UNIQUE INDEX meeting_section_receives_uk ON meeting_section (tenant_id, meeting_id)
    WHERE receives_submissions AND removed_at IS NULL;

-- Items and one level of sub-items (the services keep the level; a sub-item has no time of its own).
CREATE TABLE meeting_item (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    meeting_id           uuid        NOT NULL,
    section_id           uuid        NOT NULL,
    parent_id            uuid,
    position             int         NOT NULL CHECK (position >= 0),
    kind                 text        NOT NULL CHECK (kind IN ('SUBMISSION', 'STANDING', 'ITEM', 'BREAK')),
    standing_kind        text        CHECK (standing_kind IN ('APOLOGIES', 'DECLARATIONS', 'PREVIOUS_MINUTES', 'MATTERS_ARISING', 'AOB')),
    -- SUBMISSION items: the forum submission placed here and its memo (when the subject is a memo).
    submission_id        uuid,
    memo_id              uuid,
    title                text        NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    description          text        CHECK (length(description) BETWEEN 1 AND 2000),
    purpose              text        CHECK (purpose IN ('DECISION', 'DISCUSSION', 'INFORMATION')),
    resolution_sought    text        CHECK (length(resolution_sought) BETWEEN 1 AND 2000),
    minutes              int         CHECK (minutes BETWEEN 0 AND 600),
    -- [{userId} | {name}], at most 5.
    presenters           jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(presenters) = 'array'
                                         AND jsonb_array_length(presenters) <= 5 AND octet_length(presenters::text) <= 4096),
    -- "Only these people": user ids in the meeting's audience, checked on write; null is no restriction.
    restricted_to        uuid[]      CHECK (restricted_to IS NULL OR cardinality(restricted_to) BETWEEN 1 AND 100),
    title_hidden         boolean     NOT NULL DEFAULT false,
    classification_id    uuid        NOT NULL,
    late                 text        NOT NULL DEFAULT 'NONE' CHECK (late IN ('NONE', 'LATE', 'AWAITING_CHAIR', 'CHAIR_AGREED', 'TABLED')),
    -- The revision that was current when it was added (T3's change flags).
    added_after_revision int         NOT NULL DEFAULT 0 CHECK (added_after_revision >= 0),
    created_by           uuid,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    removed_by           uuid,
    removed_at           timestamptz,
    removed_reason       text        CHECK (removed_reason IN ('REMOVED', 'SUBMISSION_CHANGED', 'CHAIR_DECLINED', 'MEETING_CANCELLED')),
    CHECK ((kind = 'STANDING') = (standing_kind IS NOT NULL)),
    CHECK ((kind = 'SUBMISSION') = (submission_id IS NOT NULL)),
    CHECK (kind = 'SUBMISSION' OR memo_id IS NULL),
    CHECK ((kind = 'BREAK') = (purpose IS NULL)),
    CHECK (kind <> 'BREAK' OR parent_id IS NULL),
    CHECK (parent_id IS NULL OR minutes IS NULL),
    CHECK ((removed_at IS NULL) = (removed_reason IS NULL)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_agenda (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, section_id) REFERENCES meeting_section (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES meeting_item (tenant_id, id)
);
CREATE INDEX meeting_item_meeting_idx ON meeting_item (tenant_id, meeting_id);
CREATE INDEX meeting_item_section_idx ON meeting_item (tenant_id, section_id);
CREATE INDEX meeting_item_parent_idx ON meeting_item (tenant_id, parent_id) WHERE parent_id IS NOT NULL;
-- ONE_PLACEMENT: a submission is on at most one agenda at a time (a cancelled meeting's items are removed).
CREATE UNIQUE INDEX meeting_item_submission_uk ON meeting_item (tenant_id, submission_id)
    WHERE submission_id IS NOT NULL AND removed_at IS NULL;

-- People in attendance for one item only (presenters who are users included); they join the meeting's audience.
CREATE TABLE meeting_item_attendee (
    tenant_id  uuid        NOT NULL,
    item_id    uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    added_at   timestamptz NOT NULL,
    removed_at timestamptz,
    PRIMARY KEY (tenant_id, item_id, user_id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES meeting_item (tenant_id, id)
);
CREATE INDEX meeting_item_attendee_user_idx ON meeting_item_attendee (tenant_id, user_id) WHERE removed_at IS NULL;

-- Papers (ADR-0061): references to files, never copies. A memo's document, attachment or final record, or a
-- `meeting.paper` upload; published revisions pin the file version they carried (T3).
CREATE TABLE meeting_paper (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    meeting_id           uuid        NOT NULL,
    item_id              uuid        NOT NULL,
    position             int         NOT NULL CHECK (position >= 0),
    source               text        NOT NULL CHECK (source IN ('MEMO_DOCUMENT', 'MEMO_ATTACHMENT', 'MEMO_RECORD', 'UPLOAD')),
    file_id              uuid        NOT NULL,
    title                text        NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    restricted_to        uuid[]      CHECK (restricted_to IS NULL OR cardinality(restricted_to) BETWEEN 1 AND 100),
    classification_id    uuid        NOT NULL,
    late                 text        NOT NULL DEFAULT 'NONE' CHECK (late IN ('NONE', 'LATE', 'AWAITING_CHAIR', 'CHAIR_AGREED', 'TABLED')),
    added_after_revision int         NOT NULL DEFAULT 0 CHECK (added_after_revision >= 0),
    created_by           uuid,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    removed_by           uuid,
    removed_at           timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_agenda (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES meeting_item (tenant_id, id)
);
CREATE INDEX meeting_paper_item_idx ON meeting_paper (tenant_id, item_id, position);
CREATE INDEX meeting_paper_meeting_idx ON meeting_paper (tenant_id, meeting_id);
-- A file's papers: the `meeting.paper` purpose's read rule and a new version of an upload.
CREATE INDEX meeting_paper_file_idx ON meeting_paper (tenant_id, file_id);
CREATE UNIQUE INDEX meeting_paper_file_uk ON meeting_paper (tenant_id, item_id, file_id) WHERE removed_at IS NULL;

ALTER TABLE meeting_agenda ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_agenda FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_agenda FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_section ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_section FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_section FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_item FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_item_attendee ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_item_attendee FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_item_attendee FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_paper ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_paper FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_paper FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Agendas are never deleted (design E03 §6): removal is a timestamp.
REVOKE DELETE, TRUNCATE ON meeting_agenda, meeting_section, meeting_item, meeting_item_attendee, meeting_paper FROM memo_app;
