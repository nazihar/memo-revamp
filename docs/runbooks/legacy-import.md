# Legacy import

**What it is:** a tenant administrator moves memos that were decided in an earlier system into this one (ADR-0055, Phase 3 E06). A **batch** brings three things:
- **the metadata:** an XLSX workbook (sheets Memos, Files, Approvals), or the same three tables as CSV files;
- **the originals:** a folder picked in the browser and sent file by file;
- **the mappings:** each legacy status, type, unit, classification and person mapped to ours and confirmed.

A **dry run** checks everything without writing business data. The **import** then creates one imported memo per good row, with a step-up. A **verification** re-reads every stored original, and a **reconciliation report** (summary PDF, detail workbook, SHA-256 manifest and the sources used) is kept as records.

**An imported memo** is a final-state memo (`origin = IMPORTED`) with:
- its legacy number as its reference;
- its legacy facts, people and approval history as given, labelled "imported, not verified by this system";
- its originals as locked records of purpose `memo.legacy`, with PDF and text previews where possible.

It has no workflow, tasks, notifications, note sheet or final record, and is never auto-closed. People find it by search (Origin: Imported).

**Who:**
- **`memo.import`** runs batches. It is privileged (its holders need MFA, ADR-0024), granted on purpose and in no built-in role: give it through a custom role. The demo gives it to `admin@demo.example` as "Legacy importer".
- **Starting and resuming an import** need a fresh step-up. The importer must be **cleared to the highest level the batch writes** (`IMPORTER_NOT_CLEARED` otherwise). The importer does not become a participant of what they import.
- **The report** is read by holders of `memo.import` or `audit.read` who are cleared to the batch's highest level.
- **Imported memos** are read under the normal rule (ADR-0050): the linked initiator and approvers (participants), unit readers of the origin unit, later shares and copies, always within clearance.
- **Corrections and classification raises** on an imported memo need `memo.import`, the memo's read rule, a reason and a step-up.

## What must be running
| Component | Why | Without it |
|---|---|---|
| The scheduler (`MEMO_JOBS_ENABLED=true`) | `imports-dry-run`, `imports-run`, `imports-verify`, `imports-report`, `imports-purge`, and the hourly `imports-sweep` (fanning out to `imports-sweep-tenant`) | Batches stay in DRY_RUN, IMPORTING or VERIFYING; nothing is lost, the jobs catch up |
| The malware scanner (clamd) and object storage | Every source and original goes through the upload pipeline, and again at promotion | Uploads are refused `SCAN_UNAVAILABLE` (503, retried by the screen); promotion returns the row to READY until its third attempt |
| The Apache Tika server (`MEMO_TIKA_URL`, with `detect` enabled) | Types legacy `.doc`, `.xls` and `.ppt` (OLE2) for the two legacy purposes, and extracts originals' text | OLE2 originals are refused `SCAN_UNAVAILABLE` (nothing is refused for good: send them again when it is back); text previews are not possible |
| ONLYOFFICE Docs (`documents` profile) | PDF previews of Office originals | Previews are "not possible"; the originals are still imported and downloadable |

## Before the first import
1. **Finish any rolling upgrade first** (design E06 §18). An older application version cannot read a memo without an initiator, and imported memos may have none.
2. **Rehearse on a non-production tenant** with a copy of the real export: run the dry run, read the problems, fix the source, run it again, import, and read the report. Only then import into production.
3. **Size the `work` bucket.** Staged copies (sources and originals) live there until the batch is closed, which happens at the latest 30 days after completion; then they are purged. Plan for the folder's size per open batch (at most 200 GiB per batch, at most 5 open batches per tenant) plus the imported originals in the `records` bucket, which stay.
4. **Check the tenant's set-up:** each legacy type needs a memo type with a published version; each legacy unit a current unit with a code; the classification levels exist; the people who should be linked have accounts with their email (DEACTIVATED leavers link too).

## Preparing a source and the folder
- **Download the template** (Import → New batch → Metadata → template). Every column is formatted as Text, so Excel keeps leading zeros and dates as typed. Status, type, classification and unit offer the tenant's values as lists, but legacy values can still be pasted: the mappings handle them. Delete the sample rows marked "delete me".
- **Columns** (headers match without regard to case, spaces, `-` and `_`):
  - Memos: `legacy_number`, `memo_type`, `subject`, `status`, `origin_unit`, `raised_on`, `initiator_name` (required), `classification`, `decided_on` (required for APPROVED and REJECTED), `closed_on` (required for CLOSED), `initiator_email`, `initiator_id`, and `field:<key>` for a type's metadata field.
  - Files: `legacy_number`, `path`, `role` (`MAIN` or `ATTACHMENT`; one MAIN per memo) required; `title`, `sha256`, `classification` (only stricter than the memo's).
  - Approvals: `legacy_number`, `order`, `name`, `decision` required; `email`, `legacy_id`, `position`, `decided_on`, `remark`.
- **Never open a legacy CSV export in Excel before uploading it.** Excel turns numbers like `0047` into `47` and reads dates in its own way. CSV must be strict UTF-8 (a UTF-8 BOM is fine; UTF-16, UTF-32 or Windows-1252 is `NOT_UTF8`), with `,` or `;` as the delimiter.
- **Dates:** ISO text (`2019-03-14`, `2019-03-20T10:30`) or real date cells. Workbooks in the 1904 date system (old Mac Excel) are read in their own system. Nothing else is guessed: `14/03/2019` as text is `DATE_INVALID`.
- **Legacy numbers** must be text. A number held as a numeric cell is flagged `NUMBER_NOT_TEXT` (its leading zeros may be gone).
- **People:** give an email (or a legacy id that the admin maps by hand) for each person who should be linked. A person given by name only is never linked: the name shows as given.
- **The folder:** extract any archive on the desktop first. The server never unpacks anything; a `.zip` is imported as one original. System files (`.DS_Store`, `Thumbs.db`, `desktop.ini`, names starting `~$`) are skipped. Paths in the Files sheet are relative to the chosen folder, `/` or `\`.
- **Limits:** 20 MiB per source; 100 MiB per original (larger files are listed as too large and not sent); 50,000 Memos rows, 200,000 Files rows and 250,000 Approvals rows; 100 columns; 32,767 characters per value; 50 approvals and 51 originals (one MAIN, 50 attachments) per memo; 200,000 originals and 200 GiB per batch.
- **Accepted originals:** PDF, PNG, JPEG, WEBP, TIFF, text, ZIP, DOCX, XLSX, PPTX, ODT, ODS, ODP, and for these two purposes only the legacy DOC, XLS, PPT and RTF. Macros, encrypted Word and Excel files and Outlook `.msg` are refused. TIFF scans are download-only (no preview).

**When the browser is not enough.** Intake allows 600 uploads a minute per person, three at a time from the screen, so a working day carries in the order of 100,000 small files, fewer when the files are large or the link is slow. A folder much larger than a day's sending, or one that does not fit one batch (200,000 originals, 200 GiB), is split into several batches by year or unit. A command-line uploader for very large migrations is not built (design E06 §1); ask for it before planning a migration that cannot be split.

## Stages and states
**Batch** (the stepper shows where it is):

| State | Means | What can happen |
|---|---|---|
| OPEN | Being prepared: sources, originals, mappings and settings can change | Dry run; abandon |
| DRY_RUN | The dry run job runs (one at a time per batch) | Wait; the page polls |
| VALIDATED | The last dry run found no batch problem and nothing changed since | Start (step-up); any change sends it back to OPEN ("needs a new dry run"); abandon |
| IMPORTING | The import job takes READY rows, 50 per step | Stop; follow the progress |
| STOPPING | Stop asked; the job stops between two chunks | Wait |
| STOPPED | Stopped; does not hold the tenant's one running import | Resume (step-up) or Finish (the rows left become NOT_STARTED) |
| VERIFYING | Every imported memo and original is read back and re-hashed, then the report waits for the previews | Wait (up to 72 hours) |
| COMPLETED | The report is stored | Read and download it; close with a reason |
| CLOSED | Closed by an admin, or by the sweep 30 days after completion; staged copies purged | Read the report; nothing else |
| ABANDONED | Closed before importing (by an admin, or by the sweep after 30 idle days); staged copies purged | Nothing |

**Rows:** PENDING (staged) → READY or REJECTED (dry run) → IMPORTING → IMPORTED, SKIPPED (the same row already imported unchanged), REJECTED (a check failed at import time), FAILED (an original refused or its hash differs at promotion, or 3 failed attempts) or NOT_STARTED (the batch was finished after a stop). A REJECTED row is never retried in its batch: fix it and import it in a later batch, where the row key makes that safe.

**Originals:** EXPECTED (path registered) → RECEIVED (stored by the pipeline) or REFUSED (with the pipeline's reason and, for malware, the signature) → PURGED (staged copy deleted at close). A refused or received path can be sent again while the batch is OPEN or VALIDATED; the earlier staged copy is purged.

**Resuming the folder:** after a reload or on another day, choose the same folder again. The screen asks the server what it already holds and sends only the rest (a path held at another size is sent again).

## Common problems and fixes
The problem list filters by level, code, sheet and row; each problem names the sheet row and column. Messages are built from the code; the report lists them by code.

| Problem | Meaning | Fix |
|---|---|---|
| **Batch problems** (`SOURCE_MISSING`, `SHEET_MISSING`, `SHEET_UNKNOWN`, `COLUMN_REQUIRED`, `COLUMN_UNKNOWN`, `COLUMN_DUPLICATE`, `HEADER_MISSING`, `TOO_MANY_ROWS`, `TOO_MANY_COLUMNS`, `NOT_UTF8`, `DELIMITER_UNKNOWN`, `SOURCE_KIND_MIXED`, `MALFORMED`, `PARSE_TIMEOUT`, `TOO_MANY_PROBLEMS`) | Nothing proceeds; the batch stays OPEN with no staged rows | Fix the workbook or CSV and upload it again. `MALFORMED` also covers a DTD, an external entity or a zip bomb in a workbook: export it again from the source system |
| `DRY_RUN_FAILED` | The dry run failed three times | Check the backend log for the batch id (an infrastructure failure, usually the database or storage), then run the dry run again |
| **Row errors** (`VALUE_REQUIRED`, `DATE_INVALID`, `DATE_ORDER`, `DATE_FUTURE`, `SUBJECT_TOO_LONG`, `FIELD_INVALID`, `MAIN_FILE_MISSING`, `MAIN_FILE_MULTIPLE`, `TOO_MANY_FILES`, `FILE_NOT_RECEIVED`, `FILE_AMBIGUOUS`, `FILE_REFUSED`, `FILE_EMPTY`, `FILE_HASH_MISMATCH`, `ORPHAN_ROW`, …) | The row is REJECTED; the others import | Fix the row and run the dry run again, or import without it and bring it in a later batch |
| `MAPPING_UNCONFIRMED`, `*_UNMAPPED` | A legacy value has no confirmed mapping | Mappings → the kind's tab → choose the target and Confirm (or Confirm all proposals), then run the dry run again |
| `CLASSIFICATION_ORDER` (on save) | A stricter legacy marking would map to a lower level than a less strict one | Put the legacy markings in their real order (Up and Down) and map each so the order is kept. The rule is how "never less strict" is enforced |
| `CLASSIFICATION_BLANK` | A row has no classification and the batch has no default | Fill the column, or set a default level in the batch settings (each defaulted row is then flagged `CLASSIFICATION_DEFAULTED` and the report says so) |
| `IN_FLIGHT_NOT_IMPORTED` | The status is mapped to NOT_IMPORTED (still in progress in the legacy system) | Intended: memos in flight are not migrated. Finish them in the old system or raise them anew here |
| `NUMBER_IN_USE`, `NUMBER_DUPLICATE_IN_BATCH` | The number is already a memo's reference, or repeated in the batch | Correct the source; for clashes the source cannot fix, set a **number prefix** on the batch |
| `NUMBER_IN_LIVE_SERIES` | A published number format could still produce this number in a current or future period | Set a number prefix (for example `LEG-`), or check the type's numbering. Older periods are fine |
| `ALREADY_IMPORTED` (info) / `ALREADY_IMPORTED_CHANGED` | The same source code and number were imported before: unchanged (skipped) or with different content (refused) | Unchanged is harmless. Changed: an imported memo is never overwritten; correct it on the memo page with a reason instead |
| `PERSON_NOT_LINKED` (warning) | The person is shown by name only | Add an email or map the legacy id if they should be linked; otherwise confirm "not linked" |
| `PATH_CASE_MATCH`, `PATH_CASE_ONLY`, `FILE_UNREFERENCED`, `NUMBER_SIMILAR`, `FILE_SHARED` (warnings and FILE problems) | Imported, but worth a look | Check the path or file; nothing blocks |
| `IMPORTER_NOT_CLEARED` (start) | The importer's clearance is below the batch's highest level | A clearance administrator raises the importer's clearance, or another cleared holder starts it |
| `ANOTHER_IMPORT_RUNNING` (start, resume, finish) | Another batch of the tenant is IMPORTING, STOPPING or VERIFYING | Wait for it; one import runs per tenant |
| `ROWS_CHANGED` (start) | The READY count changed since the screen showed it | Reload the batch and start again |
| `DRY_RUN_STALE` (start) | Something the dry run read changed without sending the batch back to OPEN | Run the dry run again |
| `TOO_MANY_BATCHES` (create) | Five batches are open (not CLOSED or ABANDONED) | Close completed batches or abandon unused ones |
| 503 `SCAN_UNAVAILABLE` on uploads | clamd or, for `.doc`/`.xls`/`.ppt`, the Tika server is down | Nothing was refused: bring the service back and resume sending (the screen retries with back-off) |
| 429 on originals | More than 600 intake uploads a minute by one person | The screen backs off and retries; nothing to do |

## Stuck work and the sweep
Read the state from the batch page first: the progress shows rows done, the rate over the last ten minutes, the estimated end and whether the import is throttled. Fix stuck work through the product and the scheduler, never by editing import or memo tables.

- **Dry run stuck in DRY_RUN:** the job retries three times, then leaves the batch OPEN with `DRY_RUN_FAILED`. If the batch stays in DRY_RUN with no job in the scheduler table (`imports-dry-run`), the node stopped mid-run: the dead-execution handling restarts it.
- **Import stuck in IMPORTING or STOPPING:** the `imports-run` job takes 50 rows a step and reschedules itself. A row held IMPORTING for **15 minutes** (its job died) goes back to READY, or to FAILED `IMPORT_ERROR` after its **3** attempts; promotion is idempotent, so a retried row reuses the originals already stored. A failing step (infrastructure) is retried 10 times with back-off.
- **Throttled** ("Waiting for previews to catch up"): with **200 or more** `memo.legacy` previews pending in the tenant, the job waits **30 seconds** at a time, so live users' conversions are not stuck behind a migration. It resumes by itself. If it never does, the converter or the Tika server is down (attachments-and-previews runbook).
- **Verifying for a long time:** the verification re-reads and re-hashes every imported original (it restarts from the beginning on a retry). Then the report waits until no preview of the batch's originals is pending, looking every `memo.imports.report-wait` (default 10 minutes), and is built at the latest **72 hours** after the import finished, with the still-pending previews stated.
- **The hourly sweep** (`imports-sweep` → `imports-sweep-tenant` per tenant) only acts on batches **quiet for 15 minutes** (no row or state change), so it never disturbs live work:
  - IMPORTING or STOPPING: returns stale rows and schedules `imports-run` again (`scheduleIfNotExists`: harmless if the job is alive);
  - STOPPED: returns stale rows and repeats the originals' retention for imported memos;
  - VERIFYING: schedules the verification again, or the report once verified;
  - COMPLETED for 30 days: CLOSED; OPEN or VALIDATED idle for 30 days: ABANDONED; both by `System("legacy-import")` with a reason of its own, then purged;
  - CLOSED or ABANDONED not yet purged: schedules the purge again;
  - other batches: purges replaced staged copies (a source or original sent again).
  It logs a summary when it did something.

## Fixity mismatch
- **At verification:** an original whose content read back, or whose bound hash, differs from the SHA-256 taken at intake is a `FIXITY_MISMATCH` problem (stage VERIFY) on its Files row. It is audited `memo.legacy.mismatch` (file, version, expected and actual hashes) and logged as an error ("Original … of imported memo … does not match its intake hash"); alert on that log line and on the audit event. The report is then **UNBALANCED: FIXITY** and not valid. `VERIFY_FACTS_DIFFER` and `VERIFY_ORIGINAL_UNBOUND` say the memo or its binding changed, not the content.
- **Afterwards:** the weekly fixity job (`RecordFixity`, final-records runbook) re-reads every imported original as well as final records, auditing `memo.legacy.verified` (counts) and `memo.legacy.mismatch`.
- **What to do:** treat it as an **incident**. Object lock should make a change through S3 impossible, so look for access to the storage servers outside S3 (ADR-0005). Compare with the batch's manifest (`manifest-sha256.txt`, a record) and with the source workbook kept as a record. Keep the evidence. **Never edit the record:** if a good copy exists, bring it in through a later batch or ask for a correction; the mismatch stays in the audit trail.

## Encrypted PowerPoint and failed previews
Encrypted Word and Excel files are refused at intake (clamd: `Heuristics.Encrypted.OLE2`). An **encrypted PowerPoint** file passes clamd, the Tika server's typing and the dry run, because nothing parses an original before it is imported (ADR-0055, amendment of the T8 report). It is imported with its row; its PDF and text previews then end FAILED. The report lists it under **"Renditions failed"** (summary PDF) and in the Files sheet. The original stays the memo's record, download only, and the memo is still found by its details. If a readable copy exists, import it in a later batch or add it through a correction.

**"Stored, not bound":** originals promoted for a row that then failed (a race on the reference between the pre-check and the commit) stay stored as locked records under an unused memo id. The report lists them with their ids; the expected count is zero. They are legitimate records of the migration and are not deleted.

## Closing and purging
- **Close** (COMPLETED → CLOSED) needs a reason (1–500 characters). **Abandon** (OPEN or VALIDATED → ABANDONED) takes an optional reason; the rows the import never reached become NOT_STARTED. A STOPPED batch is finished first; it cannot be abandoned, since some of its rows may be imported.
- Both audit (`import.batch.closed`, `import.batch.abandoned`) and schedule `imports-purge`: 500 staged copies a step, each audited `file.version.purged` (the file row and its hash stay; a download answers 410), the originals marked PURGED; at the end `purged_at` and `import.batch.purged`.
- **Kept for good:** the staging rows, problems and mappings (the chain of custody), the report and its parts, the sources used, and every imported memo and original. Only staged copies of purgeable ORDINARY purposes (`imports.source`, `imports.intake`) are ever purged; records cannot be.
- The sweep closes COMPLETED batches 30 days after completion and abandons OPEN or VALIDATED batches after 30 idle days.

## Corrections and classification
- **Correct a legacy detail** (memo page, holders of `memo.import`): subject, dates, names, unit or type as given. The legacy facts stay as given; the correction is appended with before, after, your reason and name (`memo.legacy.corrected`). A subject correction updates the memo's subject and search.
- **Raise classification:** only upward, to a level you are cleared for, with a reason (`memo.legacy.reclassified`). Readers below the new level lose the memo and its originals.
- Both ask for a step-up. Nothing can lower a classification or change an imported memo's status or reference.

## Settings and constants
| Setting | Default | Where |
|---|---|---|
| `memo.imports.parse-timeout` | 120 s | per source read on the `imports-parse` pool (1 thread per node, queue of 4; a full queue retries the dry run) |
| `memo.imports.report-wait` (`MEMO_IMPORTS_REPORT_WAIT`) | `PT10M` | how often the report looks again while previews are pending |
| `IMPORT_INTAKE_PER_USER` | 600 a minute | rate limit of `imports.intake` uploads (other uploads keep `UPLOAD_PER_USER`, 30 a minute); the per-node upload semaphore still applies |
| Open batches per tenant | 5 | `ImportBatches.MAX_COUNTED`; CLOSED and ABANDONED do not count |
| Running imports per tenant | 1 | IMPORTING, STOPPING or VERIFYING (a partial unique index) |
| Originals per batch | 200,000; 200 GiB | `ImportIntake` |
| Paths per registration call | 1,000 | |
| Source size | 20 MiB | `imports.source` |
| Original size | 100 MiB | `imports.intake`, `memo.legacy` |
| Rows | 50,000 memos, 200,000 files, 250,000 approvals | per batch |
| Dry run validation chunk | 500 rows | `DryRunLimits.CHUNK`; 3 attempts; at most 20 problems kept per row, 250,000 per batch |
| Rows per import step (`imports.chunk-rows`) | 50 | `ImportRunLimits.CHUNK_ROWS` |
| Rendition backlog (`imports.rendition-backlog`) | 200 | `ImportRunLimits.RENDITION_BACKLOG`; the job then waits 30 s |
| Attempts per row | 3 | then FAILED `IMPORT_ERROR` |
| Stale row and sweep quiet time | 15 minutes | `ImportRunLimits.STALE` |
| Wait for rows held by another execution | 1 minute | `ImportRunLimits.HELD_WAIT` |
| Report built at the latest (`imports.report.max-wait`) | 72 hours after the import finished | `ImportEndLimits.REPORT_MAX_WAIT` |
| Close after completion (`imports.keep-staged-days`) | 30 days | `ImportEndLimits.KEEP_STAGED` |
| Abandon when idle (`imports.idle-days`) | 30 days | `ImportEndLimits.IDLE` |
| Verification, report and purge pages | 500 | `ImportEndLimits` |
| Sweep | hourly | `imports-sweep` |

The names in brackets are the design's (§13); except `memo.imports.parse-timeout` and `memo.imports.report-wait`, they are constants in the code, not configuration.

## Audit
`import.batch.created` / `.changed`, `import.source.attached`, `import.originals.registered`, `import.mapping.changed`, `import.dry_run.requested` / `.finished`, `import.batch.started` / `.stopped` / `.resumed` / `.finished` / `.verified` / `.completed` (with each report part's SHA-256 and the balance) / `.closed` / `.abandoned` / `.purged`; per memo `memo.imported` (in its transaction, with the batch, row fingerprint, each original's version and SHA-256, and `startedBy`); `memo.legacy.corrected`, `.reclassified`, `.verified`, `.mismatch`; and the files module's `file.version.created`, `file.upload.rejected`, `file.retention.extended`, `file.version.purged` and `file.opened` (report downloads). The jobs act as `System("legacy-import")`. Batch screens are not audited (lists); report downloads are.

## Volume
Measured by `ImportVolumeIT` (E06 log, T10; weekly in the `load` workflow) on a 14-thread laptop with the test containers, 10,000 memos with three originals of 50 KB each (30,000 originals, 1.4 GB), renditions excluded:

| Stage | Time | Design budget |
|---|---|---|
| Intake, eight people sending at once | 7.4 min (about 4,000 originals a minute) | — |
| Dry run | 1–1.5 min | < 5 min |
| Import | 35 min (about 290 memos, 870 originals a minute) | < 2 h |
| Verification | 5.5 min | — |
| Report | 8 s | — |
| Purge after closing | 6.3 min (30,001 staged versions) | — |

A 50,000-row workbook without originals dry-runs in about 4.5 minutes once its mappings are confirmed. **For planning:** one person sends at most 600 originals a minute, so a 30,000-file folder takes nearly an hour from one browser; the import itself, with previews made alongside, takes longer than the figure above, because it waits whenever 200 previews are pending.
