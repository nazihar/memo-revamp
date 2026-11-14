# Attachments, previews and document text

**What they are:**
- **Attachments:** supporting files on a memo (ADR-0046). An attachment may be classified above the memo. A reader not cleared for it sees only that a restricted attachment exists.
- **Renditions:** for every new version of a memo document or attachment, the backend builds a **PDF** to preview it and the **text** to compare versions (and later to search) as background jobs (ADR-0045). Both are encrypted like the original and can be rebuilt.

**Who:** the memo's initiator and its co-authors add, reclassify and remove attachments while the memo is a draft or returned. Everyone who may read the memo sees them, subject to each attachment's level.

## What must be running
| Component | Why | Without it |
|---|---|---|
| ONLYOFFICE Docs 10.0.0.1 (`documents` profile; `MEMO_DOCSERVER_URL`) | PDF renditions of Office and OpenDocument files | PDF previews of those files say "cannot be previewed"; PDFs and images still preview |
| Apache Tika 4.2.0 (`documents` profile; `MEMO_TIKA_URL`, internal only) | Text of documents | Comparing versions says the text is not available |
| The scheduler (`MEMO_JOBS_ENABLED=true`, the default) | Runs the rendition jobs | Renditions stay pending |

Tika has no authentication of its own: keep it on the internal network with the backend only (the Compose files and the network policies do). Its limits are in `deploy/tika/tika-config.json`.

## Settings
- **Tenant:** `memo.attachments.types` is a comma-separated list of extensions (for example `pdf,docx,xlsx,png`). It narrows the safe list; it can never add macro-enabled or legacy Office formats.
- **Installation:**
  - `MEMO_FILES_RENDITION_CONCURRENCY` (default 2 per kind and node) shares the document server with editing;
  - `MEMO_FILES_MAX_TEXT_CHARS` (default 5,000,000) is the text kept per version; longer text is cut and marked.

## Monitoring
| Signal | Meaning | Action |
|---|---|---|
| Metric `memo.files.renditions{outcome="retry"}` rising | The document server or Tika is not answering | Check both containers; the job retries up to four times |
| Audit `file.rendition.failed` with `TIMEOUT` or `SIZE` | A document took over two and a half minutes, or was too large, to convert | Expected for some very large files; they stay downloadable |
| Renditions pending for long | Jobs not running | Check the scheduler; a recovery task re-schedules lost jobs every five minutes |

## Rebuilding
Renditions live in the `work` bucket and are not records. If they are lost, or a converter version changes how files render, they can be rebuilt by setting the affected rows of `files_rendition` back to `PENDING` (database owner). The recovery task picks them up. Originals are never touched.

## Known limits
- **Editing attachments:** attachments are not edited in the browser yet. Change one by downloading it and uploading a new version.
- **Text** of every type comes from Tika; ONLYOFFICE only makes PDFs. Encrypted (password-protected) files get neither and stay downloadable.
- **Images and ZIP files** have no PDF rendition. Images preview as images; ZIP files are download-only.
