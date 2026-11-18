-- Phase 4 E03 T3 (ADR-0060, ADR-0061, design E03 §1.3, §1.4, §1.10, §6, §7): published revisions of an agenda, with an
-- immutable copy of its sections, items and papers (computed numbers and times, pinned file versions, change flags
-- against the revision before); who was told of each revision; and the chair's comments to the secretaries. Revisions are
-- append-only: the app role neither updates nor deletes them. Expand only: new tables. Members' reads and consent
-- requests are T4's.

-- One row per publication of a meeting's agenda: revision 1, 2, ...
CREATE TABLE meeting_revision (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    meeting_id      uuid        NOT NULL,
    number          int         NOT NULL CHECK (number >= 1),
    published_at    timestamptz NOT NULL,
    published_by    uuid,
    -- What changed, in one line: required from revision 2.
    note            text        CHECK (length(note) BETWEEN 1 AND 500),
    -- Whether the audience was told (the secretary may publish a minor correction quietly).
    notified        boolean     NOT NULL,
    -- Revision 1: the chair's agreement it was published with ({by, at, onBehalfBy?, note?}).
    chair_agreement jsonb       CHECK (chair_agreement IS NULL OR (jsonb_typeof(chair_agreement) = 'object'
                                       AND octet_length(chair_agreement::text) <= 4096)),
    CHECK (number = 1 OR note IS NOT NULL),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, meeting_id, number),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_agenda (tenant_id, meeting_id)
);

-- The sections as published (unnumbered headings).
CREATE TABLE meeting_revision_section (
    tenant_id    uuid    NOT NULL,
    revision_id  uuid    NOT NULL,
    section_id   uuid    NOT NULL,
    position     int     NOT NULL CHECK (position >= 0),
    title        text    NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    kind         text    NOT NULL CHECK (kind IN ('STANDARD', 'CONSENT')),
    members_only boolean NOT NULL,
    PRIMARY KEY (tenant_id, revision_id, section_id),
    FOREIGN KEY (tenant_id, revision_id) REFERENCES meeting_revision (tenant_id, id),
    FOREIGN KEY (tenant_id, section_id) REFERENCES meeting_section (tenant_id, id)
);

CREATE INDEX meeting_revision_section_section_idx ON meeting_revision_section (tenant_id, section_id);

-- The items as published, in agenda order ([position]), with the fields the paper rule reads (ADR-0061): the section's
-- members-only flag, the restriction, the hidden title, the level, the item attendees, and the parent of a sub-item (a
-- declaration on an item covers its sub-items, E04). Rows with [removed] are the items the revision took off: a struck
-- line in "What changed", never in the body.
CREATE TABLE meeting_revision_item (
    tenant_id         uuid        NOT NULL,
    revision_id       uuid        NOT NULL,
    item_id           uuid        NOT NULL,
    section_id        uuid        NOT NULL,
    parent_item_id    uuid,
    position          int         NOT NULL CHECK (position >= 0),
    -- "3", "3.1"; null for breaks and removed rows.
    number            text        CHECK (length(number) BETWEEN 1 AND 10),
    previous_number   text        CHECK (length(previous_number) BETWEEN 1 AND 10),
    kind              text        NOT NULL CHECK (kind IN ('SUBMISSION', 'STANDING', 'ITEM', 'BREAK')),
    standing_kind     text        CHECK (standing_kind IN ('APOLOGIES', 'DECLARATIONS', 'PREVIOUS_MINUTES', 'MATTERS_ARISING', 'AOB')),
    submission_id     uuid,
    memo_id           uuid,
    title             text        NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    description       text        CHECK (length(description) BETWEEN 1 AND 2000),
    purpose           text        CHECK (purpose IN ('DECISION', 'DISCUSSION', 'INFORMATION')),
    resolution_sought text        CHECK (length(resolution_sought) BETWEEN 1 AND 2000),
    minutes           int         CHECK (minutes BETWEEN 0 AND 600),
    -- Computed: the meeting's start plus the minutes before it; null for sub-items.
    starts_at         timestamptz,
    presenters        jsonb       NOT NULL CHECK (jsonb_typeof(presenters) = 'array' AND octet_length(presenters::text) <= 4096),
    attendees         uuid[]      NOT NULL CHECK (cardinality(attendees) <= 40),
    restricted_to     uuid[]      CHECK (restricted_to IS NULL OR cardinality(restricted_to) BETWEEN 1 AND 100),
    title_hidden      boolean     NOT NULL,
    members_only      boolean     NOT NULL,
    classification_id uuid        NOT NULL,
    -- As members read it: "Late paper" or "Tabled at the meeting".
    late              text        NOT NULL CHECK (late IN ('NONE', 'LATE', 'TABLED')),
    removed           boolean     NOT NULL DEFAULT false,
    changes           text[]      NOT NULL DEFAULT '{}' CHECK (changes <@ ARRAY['ADDED', 'REMOVED', 'MOVED', 'RETITLED',
                                      'TIME_CHANGED', 'PAPER_ADDED', 'PAPER_REPLACED', 'PAPER_REMOVED', 'LATE', 'TABLED']::text[]),
    CHECK (parent_item_id IS NULL OR starts_at IS NULL),
    CHECK (NOT removed OR number IS NULL),
    PRIMARY KEY (tenant_id, revision_id, item_id),
    FOREIGN KEY (tenant_id, revision_id) REFERENCES meeting_revision (tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES meeting_item (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_item_id) REFERENCES meeting_item (tenant_id, id),
    FOREIGN KEY (tenant_id, section_id) REFERENCES meeting_section (tenant_id, id)
);
CREATE INDEX meeting_revision_item_order_idx ON meeting_revision_item (tenant_id, revision_id, position);
CREATE INDEX meeting_revision_item_item_idx ON meeting_revision_item (tenant_id, item_id);
CREATE INDEX meeting_revision_item_parent_idx ON meeting_revision_item (tenant_id, parent_item_id) WHERE parent_item_id IS NOT NULL;
CREATE INDEX meeting_revision_item_section_idx ON meeting_revision_item (tenant_id, section_id);

-- The papers as published, each pinned to the file version it carried (ADR-0061). Rows with [removed] are papers the
-- revision took out.
CREATE TABLE meeting_revision_paper (
    tenant_id         uuid    NOT NULL,
    revision_id       uuid    NOT NULL,
    paper_id          uuid    NOT NULL,
    item_id           uuid    NOT NULL,
    position          int     NOT NULL CHECK (position >= 0),
    source            text    NOT NULL CHECK (source IN ('MEMO_DOCUMENT', 'MEMO_ATTACHMENT', 'MEMO_RECORD', 'UPLOAD')),
    file_id           uuid    NOT NULL,
    file_version_id   uuid    NOT NULL,
    title             text    NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    restricted_to     uuid[]  CHECK (restricted_to IS NULL OR cardinality(restricted_to) BETWEEN 1 AND 100),
    classification_id uuid    NOT NULL,
    late              text    NOT NULL CHECK (late IN ('NONE', 'LATE', 'TABLED')),
    -- Pages of the pinned version, when it is a PDF.
    pages             int     CHECK (pages >= 0),
    removed           boolean NOT NULL DEFAULT false,
    changes           text[]  NOT NULL DEFAULT '{}' CHECK (changes <@ ARRAY['ADDED', 'REPLACED', 'REMOVED', 'LATE', 'TABLED']::text[]),
    PRIMARY KEY (tenant_id, revision_id, paper_id),
    FOREIGN KEY (tenant_id, revision_id) REFERENCES meeting_revision (tenant_id, id),
    FOREIGN KEY (tenant_id, paper_id) REFERENCES meeting_paper (tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES meeting_item (tenant_id, id)
);
CREATE INDEX meeting_revision_paper_item_idx ON meeting_revision_paper (tenant_id, revision_id, item_id);
CREATE INDEX meeting_revision_paper_of_item_idx ON meeting_revision_paper (tenant_id, item_id);
-- A paper open (T4): the paper's row in the revision asked for.
CREATE INDEX meeting_revision_paper_paper_idx ON meeting_revision_paper (tenant_id, paper_id, revision_id);

-- Who was told of a revision (design E03 §9): the dedupe key of the per-recipient notifications, one per person and
-- revision, so a retried notification job tells nobody twice.
CREATE TABLE meeting_revision_notice (
    tenant_id   uuid        NOT NULL,
    revision_id uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    kind        text        NOT NULL CHECK (kind IN ('MEETING_AGENDA_PUBLISHED', 'MEETING_AGENDA_CHANGED')),
    notified_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, revision_id, user_id),
    FOREIGN KEY (tenant_id, revision_id) REFERENCES meeting_revision (tenant_id, id)
);

-- The chair's comments on the draft to the secretaries (design E03 §3): nothing changes on the agenda.
CREATE TABLE meeting_chair_comment (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    meeting_id uuid        NOT NULL,
    text       text        NOT NULL CHECK (length(text) BETWEEN 1 AND 2000),
    created_by uuid,
    created_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting_agenda (tenant_id, meeting_id)
);
CREATE INDEX meeting_chair_comment_meeting_idx ON meeting_chair_comment (tenant_id, meeting_id, created_at);

ALTER TABLE meeting_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_revision FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_revision_section ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_revision_section FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_revision_section FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_revision_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_revision_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_revision_item FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_revision_paper ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_revision_paper FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_revision_paper FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_revision_notice ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_revision_notice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_revision_notice FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

ALTER TABLE meeting_chair_comment ENABLE ROW LEVEL SECURITY;
ALTER TABLE meeting_chair_comment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_chair_comment FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- The record is sacred (AGENTS.md §3.3): published revisions, who was told and the chair's comments are append-only.
REVOKE UPDATE, DELETE, TRUNCATE ON meeting_revision, meeting_revision_section, meeting_revision_item, meeting_revision_paper,
    meeting_revision_notice, meeting_chair_comment FROM memo_app;
