-- Phase 3 E03 T2 (ADR-0048, design E03 §3): a memo-type version may carry a .docx template (a file of purpose
-- memo.template). The version keeps the exact file version, the scan of that version (tags and malformed tags, so the
-- check against the version's fields needs no file read), and the file version last previewed without error. A memo
-- records the template version its document was filled from.

ALTER TABLE memo_type_version
    ADD COLUMN template_file_id         uuid,
    ADD COLUMN template_version_id      uuid,
    ADD COLUMN template_scan            jsonb CHECK (
        template_scan IS NULL OR (jsonb_typeof(template_scan) = 'object' AND octet_length(template_scan::text) <= 1048576)
    ),
    ADD COLUMN template_scan_version_id uuid,
    ADD COLUMN template_previewed_id    uuid,
    ADD CONSTRAINT memo_type_version_template CHECK ((template_file_id IS NULL) = (template_version_id IS NULL)),
    ADD CONSTRAINT memo_type_version_template_scan CHECK ((template_scan IS NULL) = (template_scan_version_id IS NULL));

ALTER TABLE memo
    ADD COLUMN template_version_id uuid;
