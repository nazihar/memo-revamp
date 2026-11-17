# E06 Legacy import — design

> Step 3 of `AGENTS.md §5`. Decision: ADR-0055 (legacy import). Research: `research.md` (R11, R1). The owner reviews only the screen flows of E01, E03 and E05 (overview §10 Q1), so E06 has **no owner gate**: `ux-review.md` shows the flows for information, and the choices made under the delegation are listed there and in §17.

## 1. Scope
- **In:**
  - **Batches:** a tenant admin with `memo.import` creates a batch, uploads the metadata (an XLSX workbook or CSV files) and the originals (a folder, one file at a time), maps legacy values, runs a dry run, starts the import with a step-up, follows its progress, and downloads the reconciliation report.
  - **Imported memos:** a final-state memo with `origin = IMPORTED`, its legacy number as its reference, its legacy facts and approval history as given, its originals as records with PDF and text renditions, readable by linked people, unit readers and later shares.
  - **Across the product:** the memo view's legacy sections, an Imported filter in search, exclusion from dashboards' throughput, auto-close, the final-record backfill and the initiator's boxes; the weekly fixity job over imported originals; appended corrections and classification raises.
  - **A synthetic legacy sample** (workbook and folder) in the demo generators, for tests, the demo and E07.
- **Out:**
  - a command-line uploader (the path for volumes the browser cannot carry; not built);
  - server-side archive extraction or a drop folder (never; ADR-0055);
  - importing in-flight legacy memos (refused; ADR-0055);
  - per-file classification raises after import, and note-sheet comments on imported memos (carried);
  - OCR of scans (no OCR anywhere; ADR-0045);
  - a customer's own legacy sample (no pilot, §10 Q2; E07 imports a representative one).

## 2. Modules
- **`imports` (new module, package `com.memo.imports`):** batches, staging, parsing, mappings, the dry run, the import job, verification and the report. Tables `imports_*`, migrations `db/migration/imports/`. It depends on `memo`, `files`, `org`, `identity`, `authz` (through `common.authz`) and `audit`. Nothing depends on it.
- **`memo`:** what an imported memo is. It publishes **`LegacyMemos`** (check a row, create an imported memo in the caller's transaction, look up an existing import, describe the live number series) and **`ReportPages`** (draws a simple report through ADR-0052's `PageRenderer`, which stays internal). It owns the purpose `memo.legacy`.
- **`files`:** legacy formats for two purposes, `copyFrom` (promotion through the pipeline with an expected hash), purging of ordinary staged versions, a per-purpose upload rate limit, and a count of pending renditions by purpose.
- **`search`:** an `origin` column and filter; `initiator_id` becomes nullable.
- **Events:**
  - **Consumed by `imports`:** `FileVersionCreated` for `imports.source` and `imports.intake` (binds a stored file to its batch).
  - **Published by `memo`:** the existing `MemoChanged` and `MemoFileChanged` when an imported memo is created or corrected, so search indexes it in the same transaction (ADR-0054). No workflow event, so no watch or notification fires.

## 3. Domain model
```mermaid
erDiagram
  IMPORTS_BATCH ||--o{ IMPORTS_FILE : "originals received"
  IMPORTS_BATCH ||--o{ IMPORTS_ROW : "Memos rows"
  IMPORTS_ROW ||--o{ IMPORTS_ROW_FILE : "Files rows"
  IMPORTS_ROW ||--o{ IMPORTS_ROW_APPROVAL : "Approvals rows"
  IMPORTS_ROW_FILE }o--o| IMPORTS_FILE : "matched to"
  IMPORTS_BATCH ||--o{ IMPORTS_MAPPING : "legacy value -> ours"
  IMPORTS_BATCH ||--o{ IMPORTS_PROBLEM : "found"
  IMPORTS_ROW |o--o| MEMO : "imported as"
  MEMO ||--o| MEMO_LEGACY : "legacy facts"
  MEMO ||--o{ MEMO_LEGACY_APPROVAL : "legacy history"
  MEMO ||--o{ MEMO_LEGACY_FILE : originals
  MEMO_LEGACY_FILE }o--|| FILES_FILE : "purpose memo.legacy"
  MEMO ||--o{ MEMO_LEGACY_CORRECTION : "appended corrections"
```

### 3.1 Memo module (`memo/V19__legacy.sql`)
- **`memo` changes:**
  - `origin text NOT NULL DEFAULT 'NATIVE' CHECK (origin IN ('NATIVE', 'IMPORTED'))`.
  - `initiator_id` becomes nullable, with `memo_initiator_native CHECK (origin = 'IMPORTED' OR initiator_id IS NOT NULL)`.
  - V1's unnamed CHECK `(status IN ('DRAFT', 'DISCARDED')) = (workflow_instance_id IS NULL)` is replaced by `memo_workflow_by_status CHECK (origin = 'IMPORTED' OR …)`. The migration looks the old constraint up by its definition in `pg_constraint`, because V1 did not name it.
  - `memo_imported_shape CHECK (origin = 'NATIVE' OR (status IN ('APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED', 'CLOSED') AND workflow_instance_id IS NULL AND current_round = 0 AND reference IS NOT NULL AND number_series IS NULL AND document_file_id IS NULL AND approved_at IS NULL AND memo_type_version_id IS NOT NULL))`.
  - V9's `memo_closed_at` CHECK still holds: an imported CLOSED memo has `closed_at` (the legacy closing date, see below).
  - **Trigger `memo_imported_guard`** (BEFORE UPDATE): `origin` never changes. On an imported memo only `subject` (a correction), `classification_id` and `classification_rank` (raised only), `updated_at` and `version` may change. The existing `memo_number_guard` and `memo_pinned_guard` already keep the reference, type and metadata.
  - Partial index `memo_tenant_imported_idx (tenant_id, created_at) WHERE origin = 'IMPORTED'`.
- **`memo_legacy`** (one row per imported memo; append-only):
  - `tenant_id`, `memo_id` (primary key with the tenant), `batch_id`, `row_number`;
  - `source_code` (`^[A-Z0-9][A-Z0-9-]{0,19}$`), `legacy_number` (as given, 1–100), `legacy_number_key` (normalised: trimmed, inner spaces collapsed, NFC, upper case);
  - `row_fingerprint bytea` (32 bytes), `time_zone` (IANA name);
  - `raised_on date NOT NULL`, `decided_on date`, `closed_on date`;
  - as given: `legacy_status`, `legacy_type`, `legacy_classification`, `legacy_unit` (each at most 200), `subject_as_given` (1–300);
  - `initiator_name` (1–200), `initiator_email`, `initiator_legacy_id` (at most 100), `initiator_user_id` (null unless linked);
  - `imported_at`, `imported_by` (who started the batch);
  - `UNIQUE (tenant_id, source_code, legacy_number_key)`: the row key; index `(tenant_id, batch_id)`.
- **`memo_legacy_approval`** (append-only): `tenant_id`, `memo_id`, `ordinal` (1–50), `name` (1–200), `email`, `legacy_id`, `user_id` (null unless linked), `position_text` (at most 200), `decision` (as given, 1–100), `decided_on date`, `remark` (at most 4,000). Primary key `(tenant_id, memo_id, ordinal)`.
- **`memo_legacy_file`** (append-only): `id`, `tenant_id`, `memo_id`, `file_id` (purpose `memo.legacy`), `role` (`MAIN` or `ATTACHMENT`), `position`, `title` (1–255), `relative_path` (1–1,024), `classification_id` and `classification_rank` (null: the memo's level; set: strictly above it), `sha256 bytea`, `size_bytes`. `UNIQUE (tenant_id, file_id)`; one MAIN per memo (partial unique index); index `(tenant_id, memo_id, position)`.
- **`memo_legacy_correction`** (append-only): `id`, `tenant_id`, `memo_id`, `field` (`SUBJECT`, `RAISED_ON`, `DECIDED_ON`, `CLOSED_ON`, `INITIATOR_NAME`, `LEGACY_UNIT`, `LEGACY_TYPE`, `CLASSIFICATION`), `value_before`, `value_after` (each at most 300), `reason` (1–500), `corrected_by`, `corrected_at`. Index `(tenant_id, memo_id, corrected_at)`.
- **Append-only:** `REVOKE UPDATE, DELETE, TRUNCATE` on the four legacy tables from `memo_app`.
- **Invariants:**
  1. An imported memo is in a final state, has a reference, no number series, no workflow instance, no document file, no final record and no `approved_at`.
  2. Its legacy facts, approvals and originals never change; a correction is a new row, and only the subject and the classification of the memo row follow it.
  3. A linked person (`*_user_id`) was linked by an explicit identifier confirmed in the batch's people mapping (§6.4); the importer is never written as a person.
  4. Exactly one MAIN original; a file's rank is at least the memo's.
  5. `(source_code, legacy_number_key)` is unique in the tenant.
- **Dates on the memo row:**
  - `created_at` and `updated_at` are the import time (TNA: never reset dates silently; the legacy dates are kept as given in `memo_legacy`).
  - `closed_at` (CLOSED only) is the start of `closed_on` in the batch's time zone, because V9's CHECK needs it. The view shows the legacy date, never this instant.
  - `approved_at` stays null, so auto-close (`MemoClosing.due`) and E04's backfill never see imported memos (they also filter on `origin`).

### 3.2 Imports module (`imports/V1__imports.sql`)
- **`imports_batch`:**
  - `id`, `tenant_id`, `name` (1–200), `source_code`, `time_zone`, `default_classification_id` (optional), `number_prefix` (optional, at most 20, `^[A-Za-z0-9][A-Za-z0-9/._-]{0,19}$`);
  - `state` (§4), `version` (optimistic lock), `created_by`, `created_at`, `updated_at`;
  - sources: `source_kind` (`XLSX` or `CSV`), and for each table (`WORKBOOK`, `MEMOS`, `FILES`, `APPROVALS`) the staged `…_file_id` and `…_version_id`;
  - `input_hash bytea` (§5.2) and `dry_run_hash`, `dry_run_at`;
  - `started_by`, `started_at`, `step_up_at`, `finished_at`, `completed_at`, `closed_at`, `close_reason`;
  - `max_rank` (highest level written), `totals jsonb` (the report's counts, at most 16 KB), `report jsonb` (file and version ids and SHA-256s of the report parts, `balanced`).
  - Partial unique index `imports_batch_one_running (tenant_id) WHERE state IN ('IMPORTING', 'STOPPING', 'VERIFYING')`: one running import per tenant.
- **`imports_file`** (an original received): `id`, `tenant_id`, `batch_id`, `path` (NFC, `/` separators, 1–1,024 characters, at most 32 segments), `path_fold` (case-folded, for the case-insensitive match), `size_bytes`, `state` (`EXPECTED`, `RECEIVED`, `REFUSED`, `PURGED`), `file_id`, `version_id`, `sha256`, `media_type`, `refusal` (the pipeline's reason code), `received_at`. `UNIQUE (tenant_id, batch_id, path)`; indexes `(tenant_id, batch_id, path_fold)` and `(tenant_id, batch_id, state, path)`.
- **`imports_row`** (a Memos row): `id`, `tenant_id`, `batch_id`, `row_number`, `legacy_number`, `legacy_number_key`, `data jsonb` (the canonical values as strings, at most 256 KB), `fingerprint bytea`, `result` (§4.2), `memo_id` (assigned when the import starts; UUIDv7), `errors`, `warnings`, `attempts`, `updated_at`. `UNIQUE (tenant_id, batch_id, row_number)`; indexes `(tenant_id, batch_id, legacy_number_key)` and `(tenant_id, batch_id, result, row_number)`.
- **`imports_row_file`** (a Files row): `id`, `tenant_id`, `batch_id`, `row_number`, `legacy_number_key`, `path`, `role`, `title`, `expected_sha256`, `classification` (as given), `row_id`, `file_id` (the matched `imports_file`), `match` (`EXACT`, `CASE`, `NONE`, `AMBIGUOUS`). Indexes `(tenant_id, batch_id, legacy_number_key)` and `(tenant_id, row_id)`.
- **`imports_row_approval`** (an Approvals row): `tenant_id`, `batch_id`, `row_number`, `legacy_number_key`, `ordinal`, `name`, `email`, `legacy_id`, `position`, `decision`, `decided_on` (as given), `remark`, `row_id`. Index `(tenant_id, row_id, ordinal)`.
- **`imports_mapping`:** `tenant_id`, `batch_id`, `kind` (`STATUS`, `TYPE`, `CLASSIFICATION`, `UNIT`, `PERSON`), `legacy_value` (1–500), `legacy_order` (CLASSIFICATION only), `target` (a status name or an id), `proposed` (boolean), `confirmed_by`, `confirmed_at`, `row_count`. Primary key `(tenant_id, batch_id, kind, legacy_value)`.
- **`imports_problem`:** `id`, `tenant_id`, `batch_id`, `stage` (`DRY_RUN`, `IMPORT`, `VERIFY`), `level` (`BATCH`, `ERROR`, `WARNING`, `FILE`, `INFO`), `code`, `sheet` (`MEMOS`, `FILES`, `APPROVALS`, `ORIGINALS`, `BATCH`), `row_number`, `column_name`, `row_id`, `file_id`, `params jsonb` (at most 2 KB; values, never subjects). Index `(tenant_id, batch_id, level, code, id)`.
- No foreign keys to other modules' tables (each module migrates alone, ADR-0007); ids are checked by the services. Staging rows are kept after the batch closes (they are small and part of the chain of custody); only the staged **objects** are purged.

### 3.3 Files module (`files/V4__purge.sql`)
- `files_version.purged_at timestamptz` and `purged_by`: the object is deleted from the `work` bucket, the row and its hash stay. A purged version answers 410 to downloads.
- Allowed only for ORDINARY purposes that declare `purgeable` (only `imports.intake` and `imports.source`); record versions can never be purged (CHECK in code and an architecture test; the bucket's object lock refuses anyway).
- **As built (T2):**
  - `FilePurges.purge(versionId)` marks the version and audits `file.version.purged` in one transaction, then deletes the object. A repeat answers false and deletes the object again, so a crash in between leaves nothing behind.
  - The database holds the rule too: a CHECK (`purged_at` only on `work`, unlocked versions) and the `files_version` guard, which now lets `purged_at` and `purged_by` be set once and nothing else change with them.
  - A purgeable purpose may have no renditions (their objects would stay behind); the start-up check refuses it.
  - Downloads of a purged version answer 410 with `reason: PURGED`; `copyFrom` refuses it. `FileVersionInfo.purgedAt` tells owning modules.

### 3.4 Search module (`search/V3__origin.sql`)
- `search_memo`, `search_note` and `search_chunk` gain `origin text NOT NULL DEFAULT 'NATIVE'` (each part filters on its own table, per ADR-0054's "one table per part").
- `search_memo.initiator_id` becomes nullable.
- `search_put_memo` takes `p_origin`; `search_find` and `search_find_sql` take `p_origin text[]` (null: both). The functions are replaced in the same migration, keeping `search_path`, owner and grants; `RlsMetaTest`'s exception list does not change.

## 4. Lifecycles
### 4.1 Batch
```mermaid
stateDiagram-v2
  [*] --> OPEN: created
  OPEN --> DRY_RUN: dry run requested
  DRY_RUN --> VALIDATED: done
  DRY_RUN --> OPEN: job failed after retries (DRY_RUN_FAILED)
  VALIDATED --> OPEN: a source, an original, a mapping or a setting changed
  VALIDATED --> IMPORTING: start (step-up, nothing changed)
  IMPORTING --> STOPPING: stop
  STOPPING --> STOPPED: between chunks
  STOPPED --> IMPORTING: resume (step-up)
  STOPPED --> VERIFYING: finish (rows left are NOT_STARTED)
  IMPORTING --> VERIFYING: last row done
  VERIFYING --> COMPLETED: verification done, renditions settled, report stored
  COMPLETED --> CLOSED: close, or 30 days after completion
  OPEN --> ABANDONED: close (nothing imported), or 30 days idle
  VALIDATED --> ABANDONED: close, or 30 days idle
  CLOSED --> [*]
  ABANDONED --> [*]
```
- **Forbidden:** any change to sources, originals, mappings or settings outside OPEN and VALIDATED (409 `WRONG_STATE`); a start from anything but VALIDATED; a second batch entering IMPORTING, STOPPING or VERIFYING in the tenant (`ANOTHER_IMPORT_RUNNING`, backed by the partial unique index); leaving CLOSED or ABANDONED; closing while DRY_RUN, IMPORTING, STOPPING or VERIFYING.
- Every transition is a guarded update on `(id, state, version)` (AGENTS §4); 0 rows is a conflict.
- **At most 5 batches** per tenant may be in a state other than CLOSED or ABANDONED (`TOO_MANY_BATCHES`).

### 4.2 Row
```mermaid
stateDiagram-v2
  [*] --> PENDING: staged from the sheet
  PENDING --> READY: dry run, no error
  PENDING --> REJECTED: dry run, an error
  READY --> REJECTED: a later dry run finds an error
  REJECTED --> READY: a later dry run finds none
  READY --> IMPORTING: the job takes it (guarded)
  IMPORTING --> IMPORTED: committed
  IMPORTING --> SKIPPED: same key and fingerprint already imported
  IMPORTING --> REJECTED: a check fails at import time
  IMPORTING --> FAILED: a file refused or its hash differs at promotion
  IMPORTING --> READY: stale for 15 minutes (crash), retried
  READY --> NOT_STARTED: batch finished after a stop
```
- **Final:** IMPORTED, SKIPPED, REJECTED (once the import started), FAILED, NOT_STARTED. A row is imported at most once (the guarded update, the unique row key and the promotion idempotency keys).
- **A dry run never moves a row to IMPORTING**, and an import never re-runs a REJECTED row of the same batch: it goes into a later batch after fixing, where the key makes that safe.

### 4.3 Original received
`EXPECTED` (path registered) → `RECEIVED` (stored by the pipeline) or `REFUSED` (the pipeline's reason: `MALWARE`, `TYPE_NOT_ALLOWED`, `TYPE_MISMATCH`, `ENCRYPTED`, `TOO_LARGE`, `MALFORMED`, `LIMIT_EXCEEDED`) → `PURGED` (staged object deleted). A RECEIVED file can be replaced by uploading the same path again while the batch is OPEN or VALIDATED (it then returns to OPEN); the earlier staged version is purged.

## 5. Stages
### 5.1 Intake
- **Sources:** `POST /api/v1/files?purpose=imports.source&owner={batchId}:{table}` with `table` one of `WORKBOOK`, `MEMOS`, `FILES`, `APPROVALS`. A WORKBOOK clears any CSV sources and the reverse. The `FileVersionCreated` listener binds the version to the batch (in the upload's transaction) and moves a VALIDATED batch back to OPEN.
- **Originals:**
  1. The client lists the chosen folder, drops system files (`.DS_Store`, `Thumbs.db`, `desktop.ini`, names starting `~$`; counted as skipped) and normalises each relative path (NFC, `/`).
  2. It registers paths in groups of at most 1,000: `POST /api/v1/admin/imports/{id}/files` `{files: [{path, size}]}`. The answer gives each path's intake id and state. New paths are EXPECTED; known paths keep their state.
  3. It uploads each EXPECTED file, three at a time: `POST /api/v1/files?purpose=imports.intake&owner={intakeId}` with `Idempotency-Key: imp-{intakeId}-{size}`.
  4. The listener marks the entry RECEIVED with the version, SHA-256 and detected type; a pipeline refusal marks it REFUSED with the reason (the imports module listens to `file.upload.rejected` through a files event `FileUploadRejected(tenantId, purposeKey, ownerId, fileId, idempotencyKey, reasonCode, signatureName)`, added in T2). The event is published in the transaction that audits the refusal. It is not published for `SCAN_UNAVAILABLE` (scanner or Tika server down): that refuses nothing, and the client uploads again. (Named `FileUploadRejected` because the files module's internal exception is already `UploadRejected`.)
- **Path rules** (server; the client applies the same): no empty segment, `.` or `..`, no leading `/`, no `\` (converted by the client), no control or format characters, at most 1,024 characters and 32 segments (`PATH_INVALID`). Two paths equal after NFC are one path (`PATH_DUPLICATE`). Paths that differ only in case are both kept and flagged (`PATH_CASE_ONLY`, a warning, as BagIt advises).
- **Resume:** after a reload or on another day, the admin picks the same folder; the client lists `GET …/files?state=EXPECTED,REFUSED` (pages of 5,000) and uploads only paths the server does not hold at the same size. A path held at another size is uploaded again (replacing it).
- **Rate limit:** uploads of purpose `imports.intake` use their own bucket, `IMPORT_INTAKE_PER_USER` (600 a minute), instead of `UPLOAD_PER_USER` (30 a minute, which would take 14 hours for 25,000 files). The per-node upload semaphore (8) still applies. A purpose chooses its limit with `FilePurpose.uploadRateLimit` (one of the two, checked at start-up; T2).
- **Legacy formats (T2):** for a purpose with `acceptsLegacyFormats`, an OLE2 container is typed by the Tika server's `PUT /detect` after clamd has scanned it. Word, Excel and PowerPoint are allowed. Anything else is `TYPE_NOT_ALLOWED` (an Outlook message), and encrypted OOXML in its OLE2 wrapper is `ENCRYPTED`. If no Tika server is configured, or it cannot answer, the upload is refused as `SCAN_UNAVAILABLE` (503 with `Retry-After`; no new reason code, because the web already handles that one). Old Office types that the in-process magic names without an OLE2 container (Word for DOS and similar) are refused everywhere: only the server names a .doc, .xls or .ppt. RTF and TIFF are recognised in process.

### 5.2 Dry run
Requested with `POST …/dry-run`; runs as the `imports-dry-run` job in four steps, each resumable:
1. **Stage** (write transactions on staging tables only): parse the sources (§7) into `imports_row`, `imports_row_file` and `imports_row_approval`, replacing the previous dry run's staging rows. Batch-level problems (§9) stop here.
2. **Group and match** (staging only): attach Files and Approvals rows to their Memos row by `legacy_number_key`; match each Files row's path to an `imports_file`: exact NFC match, else a unique case-insensitive match (`PATH_CASE_MATCH` warning), else `AMBIGUOUS` or `NONE`.
3. **Propose mappings** (staging only): collect the distinct legacy values per kind with their counts; add new ones with a proposal (§6.4); keep confirmed ones.
4. **Validate**, in chunks of 500 rows, each in a `readInTenant` transaction, which PostgreSQL holds read-only (the T1 spike: pgjdbc begins it `READ ONLY`, and a write fails with SQLSTATE 25006; a test keeps it so). The validators are the import's own (`LegacyMemos.check` plus the imports module's row rules, §9). Each chunk's results (row states and problems) are written afterwards in a separate write transaction on staging tables.
- **Input hash:** SHA-256 over the source versions and their hashes, the RECEIVED originals (path and SHA-256, in path order), the mappings (kind, value, order, target) and the settings. Stored as `dry_run_hash` when the dry run starts. A start is refused unless the batch is VALIDATED, which no change since the dry run allows.
- **Cross-row checks** run as set-based queries over the staging rows: duplicate legacy numbers in the batch, legacy numbers that equal an existing reference (`memo.reference`, after the batch prefix), numbers that differ from another only in case or separators (`NUMBER_SIMILAR`, a warning, because search's `ref_norm` would find both), and the same file content referenced by two rows (`FILE_SHARED`, a warning).
- **Live series check:** `LegacyMemos.series()` gives each published type version's number format as a pattern (TEXT and TYPE_CODE literal, UNIT_CODE as a unit code, YEAR, FINANCIAL_YEAR, SEQUENCE as digits). A legacy number (with the prefix) that matches a pattern **and** whose period is the current or a later one (or the series never resets) **and** whose sequence is above that period's counter is `NUMBER_IN_LIVE_SERIES`: a native memo could later take it. Older periods cannot be produced again; the unique reference index stays the last guard.
- **Clearance:** the dry run records the highest level the READY rows and their files would write (`max_rank`). The start is refused if the importer is not cleared to it (`IMPORTER_NOT_CLEARED`).
- **Nothing else is written.** A test runs a dry run and compares every business table's row count and a checksum before and after; another writes inside the validation transaction and expects SQLSTATE 25006.

### 5.3 Import
- **Start:** `POST …/start` (step-up, `If-Match`, `{expectedRows}`): VALIDATED → IMPORTING; `expectedRows` must equal the READY count the screen showed (409 `ROWS_CHANGED` otherwise). Each READY row gets its `memo_id` (UUIDv7). Audited `import.batch.started`.
- **The job** `imports-run` (tenant one-off, instance id `{batchId}`, actor `System("legacy-import")`) takes READY rows in chunks of 50 in row order, then reschedules itself:
  1. **Throttle:** if 200 or more renditions of purpose `memo.legacy` are PENDING in the tenant (`FileRenditions.pendingCount(purposeKey, atMost)`, a count that stops at `atMost`), reschedule in 30 seconds. Live users' conversions are not stuck behind a migration.
  2. **Stop:** if the batch is STOPPING, move it to STOPPED and end.
  3. **Take** the row: guarded update READY → IMPORTING (`attempts + 1`).
  4. **Pre-check** in a read-only transaction: `LegacyMemos.existing(source, key)` (same fingerprint → SKIPPED; different → REJECTED `ALREADY_IMPORTED_CHANGED`) and `LegacyMemos.check(row)` again (a type unpublished, a unit closed or a number taken since the dry run → REJECTED with its code).
  5. **Promote** each original, outside a transaction: `FileUploads.copyFrom(intakeVersionId, "memo.legacy", memoId, idempotencyKey = "imp-{batchId}-{rowFileId}", expectedSha256)`. The files module streams the decrypted staged content through the full pipeline (scan with current signatures, detection, encryption, record storage with the memo's retention). A hash that differs is refused before anything is stored (`HASH_MISMATCH`). A refusal makes the row FAILED with the file's reason. As built (T2): `copyFrom` throws `UploadRefusedException` with the reason code (`SCAN_UNAVAILABLE` is worth a retry); it keeps the staged name, does not count against an upload rate (the job is not a person), reads the source without an access check (the caller decides), and refuses a staged copy whose ciphertext fails authentication as `HASH_MISMATCH` too.
  6. **Commit** in one transaction: `LegacyMemos.create(...)` writes the memo, `memo_legacy`, approvals, participants, `memo_legacy_file` rows and the `memo.imported` audit entry, and publishes `MemoChanged` and `MemoFileChanged`; the row becomes IMPORTED and its files are marked imported. A failure rolls everything back; the row returns to READY for a retry (at most 3 attempts, then FAILED `IMPORT_ERROR`).
- **Crash:** db-scheduler's dead-execution handling restarts the job; the sweep (§10) returns rows IMPORTING for more than 15 minutes to READY. Promotion is idempotent by key, so a retried row reuses the versions already stored.
- **Files promoted for a row that then fails** (a race on the reference between pre-check and commit) stay stored under the reserved memo id. The report lists them as "stored, not bound" with their ids (§8). This is rare; the residual risk is in the threat model.
- **Last chunk:** IMPORTING → VERIFYING, audited `import.batch.finished`.

### 5.4 Verification and report
- **`imports-verify`:** re-reads every original stored by the batch (the `memo.legacy` versions), decrypts, re-hashes and compares with the intake SHA-256. A mismatch adds `FIXITY_MISMATCH` (stage VERIFY), audits `memo.legacy.mismatch` and raises an alert (runbook). Audited `import.batch.verified` with counts.
- **`imports-report`:** runs every 10 minutes while the batch is VERIFYING. It builds the report once no rendition of the batch's originals is PENDING (`FileRenditions.pendingCount(versionIds)`), or 72 hours after the import finished, whichever is first (still-pending renditions are then a stated category). It stores the parts (§8) as files of purpose `imports.record`, writes their ids and hashes into `imports_batch.report`, audits `import.batch.completed` with the SHA-256s and the balance, and moves the batch to COMPLETED.
- **Chain of custody:** at start, each source version is also copied into `imports.record` (`copyFrom`), so the workbook actually used is a record.

## 6. Mapping legacy data
### 6.1 Columns
Headers are matched case-insensitively, with spaces, `-` and `_` treated alike ("Legacy number" = `legacy_number`). Unknown, duplicate or missing required columns are batch problems.

| Table | Columns (* required) |
|---|---|
| Memos | `legacy_number`*, `memo_type`*, `subject`*, `status`*, `classification`, `origin_unit`*, `raised_on`*, `decided_on` (required for APPROVED and REJECTED), `closed_on` (required for CLOSED), `initiator_name`*, `initiator_email`, `initiator_id`, and `field:<fieldKey>` for the mapped type's metadata fields |
| Files | `legacy_number`*, `path`*, `role`* (`MAIN` or `ATTACHMENT`), `title`, `sha256`, `classification` |
| Approvals | `legacy_number`*, `order`*, `name`*, `email`, `legacy_id`, `position`, `decision`*, `decided_on`, `remark` |

- A `field:<key>` column is allowed when some active type has that key. Per row, a value is checked with `MemoMetadata` rules for the row's mapped type, without `requireAll` (required fields are a submit rule).
- A legacy number held as a numeric cell gives `NUMBER_NOT_TEXT` (a warning: leading zeros may have been lost).

### 6.2 Statuses and dates
- STATUS maps each legacy value to `APPROVED`, `REJECTED`, `WITHDRAWN`, `CANCELLED`, `CLOSED` or `NOT_IMPORTED` (in flight: the row is refused with `IN_FLIGHT_NOT_IMPORTED`). A value equal to one of ours (case-insensitive) is proposed as itself.
- Dates: `raised_on` ≤ `decided_on` ≤ `closed_on`, none after today in the batch's time zone, none before 1900-01-01 (`DATE_ORDER`, `DATE_FUTURE`, `DATE_INVALID`). Times, if given, are kept for display; dates without a time are normal.

### 6.3 Types, units and classification
- **TYPE:** each legacy value maps to a memo type with a published version (proposed when it equals a type's code or name). The memo is pinned to that version (`memo_type_version_id`).
- **UNIT:** each legacy value maps to a current unit (proposed when it equals a current unit's code). The legacy text is kept in `memo_legacy.legacy_unit`. The origin unit is required, because it drives `memo.read.unit` (ADR-0050).
- **CLASSIFICATION:**
  - each legacy value maps explicitly to a tenant level (proposed when it equals a level's name), and the admin gives the legacy values' order (`legacy_order`, 1 = least strict);
  - the mapping must keep the order: if `a < b` in legacy order, then `level(a) ≤ level(b)` (`CLASSIFICATION_ORDER`, refused on save);
  - a blank cell is `CLASSIFICATION_BLANK` unless the batch has a default level, which every such row then gets (`CLASSIFICATION_DEFAULTED`, a warning; the report states it);
  - a Files row's level must be strictly above the memo's or blank (`FILE_CLASSIFICATION_LOWER`).

### 6.4 People
- PERSON mapping rows are keyed by the identifier given: `email:<lower-case email>` or `id:<legacy id>`.
- **Email:** proposed when it equals a user's email in the tenant (any status: a DEACTIVATED leaver links too). **Legacy id:** never proposed; the admin may map it to a user through the people picker.
- **A person given by name only is never linked** and has no mapping row. The admin can add an email or id in the workbook and re-run the dry run.
- An unlinked initiator or approver is `PERSON_NOT_LINKED` (a warning): the row is imported, the name shows as given, `initiator_id` is null.
- **Linked people** become participants (`INITIATOR` for the initiator, `APPROVER` for approvers), so they can read the memo under clearance. A person who is not cleared for the memo's level stays linked in the legacy record but cannot read it, as for any participant.
- The directory has no employee numbers today (identity holds email only), so the research's "employee number" identifier is not offered.

### 6.5 Confirmation
- Every mapping row must be **confirmed** before rows that use it can be READY; an unconfirmed one gives the row `MAPPING_UNCONFIRMED`. "Confirm all proposals" exists per kind, and the mapping screen shows the count of rows each value affects.
- Saving mappings (`PUT …/mappings/{kind}`) moves a VALIDATED batch back to OPEN; the dry run is run again.

## 7. Parsing (sources)
- **Where:** `com.memo.imports.internal.sheet`, on a dedicated pool `imports-parse` (1 thread per node, queue 4) with a 120-second limit per source and a catch-all mapping any `Throwable` (including `StackOverflowError` and `OutOfMemoryError` from POI) to `MALFORMED`.
- **XLSX** (POI 6.0.0 on `poi-ooxml-lite`):
  - `OPCPackage` over a decrypted temporary copy of the source in the private spool directory, encrypted at rest like upload spools (ADR-0029); `XSSFReader` with `ReadOnlySharedStringsTable` and `StylesTable`; a SAX `SheetContentsHandler` per sheet.
  - The `ZipSecureFile` limits (64 MiB per entry, ratio 0.01) are set at start-up by a shared initialiser used by templating and imports (today they are set in `PoiTlFiller.limitZips`).
  - Sheets are found by name (`Memos`, `Files`, `Approvals`; `Instructions` and `Lists` ignored); any other sheet is a batch problem (`SHEET_UNKNOWN`).
  - Date cells: a numeric cell with a date format (`DateUtil.isADateFormat`), read in the workbook's date system (`date1904` from `workbook.xml`; POI's streaming handler assumes 1900, so the reader's `DataFormatter` applies the system, as the T1 spike showed). Formula cells: the cached value with `FORMULA_CACHED`; none is `FORMULA_NO_VALUE`.
  - XML: POI's own secure parser settings; a DTD anywhere is refused (`MALFORMED`), which the corpus checks.
- **CSV** (Commons CSV 1.14.1): `BOMInputStream` (UTF-8 BOM only; a UTF-16 or UTF-32 BOM is `NOT_UTF8`), a strict `CharsetDecoder` (`REPORT` on malformed input), `CSVFormat.RFC4180` with header, `setDuplicateHeaderMode(DISALLOW)`, `setAllowMissingColumnNames(false)`, `setTrim(true)`, `setMaxRows` per table; delimiter `,` or `;` chosen by which one splits the first line into known column names (`DELIMITER_UNKNOWN` otherwise).
- **Caps:** 20 MiB per source, 100 columns, 32,767 characters per value, 50,000 Memos rows, 200,000 Files rows, 250,000 Approvals rows; at most 50 approvals and 51 files (one MAIN, 50 attachments, ADR-0046) per memo.
- **The template** (`GET …/template`): written with POI XSSF on request; sheets Instructions, Memos, Files, Approvals, Lists; every column formatted as Text; data validation on status, type, classification and unit as **warning** lists (legacy values can still be pasted; the mappings handle them); a sample row per sheet marked "delete me". The Instructions sheet says: never open a legacy CSV export in Excel before uploading; extract archives on the desktop; one MAIN per memo.

## 8. The reconciliation report
- **Parts** (purpose `imports.record`, owner the batch):
  1. `report-summary.pdf`, drawn through `ReportPages` (ADR-0052's renderer, English only, §10 Q3);
  2. `report-detail.xlsx` (POI SXSSF, string cells only; values starting with `=`, `+`, `-`, `@`, tab, CR, LF or their full-width forms prefixed by `'`), sheets Summary, Rows, Files, Problems, Mappings;
  3. `manifest-sha256.txt`: `<sha256>  <path>` per stored original, as RFC 8493 §2.1.3 (plain text, never a spreadsheet);
  4. the sources used, copied at start (§5.4).
- **Header:** tenant, batch id and name, source code; who created, started and finished it, and when (UTC); the step-up time; each source's name, size and SHA-256; the time zone; each mapping kind with its hash; the default classification if used; the number prefix; the software version.
- **Totals and balance lines** (the summary says BALANCED or UNBALANCED, and names each failing line):
  - **Rows:** read = imported + rejected + skipped + failed + not started; rejected broken down by code.
  - **Files listed:** Files rows = matched + not received + ambiguous.
  - **Originals received:** registered = stored + refused + never uploaded; stored = imported + held back (only rows not imported use them) + unreferenced; bytes likewise.
  - **Renditions:** for each kind (PDF, TEXT), imported originals = done + failed + not possible + pending (pending is non-zero only after the 72-hour cap).
  - **Fixity:** verified = imported originals; mismatches = 0.
  - **Stored, not bound:** originals promoted for rows that then failed, listed with ids (expected 0).
- **Rows sheet:** row number, legacy number as given and as stored (with prefix), result, codes and messages, memo id, every date as given and as stored, linked people (user ids only).
- **Files sheet:** path, size, SHA-256 at intake, expected (if given) and stored, verification result, file and version ids, PDF and TEXT rendition states, or the refusal reason.
- **Classification:** the report parts are classified at the batch's `max_rank`; `mayRead` requires `memo.import` or `audit.read`, and clearance to that level. Downloads are audited (`file.opened`).
- **Complete and valid** (BagIt's words): complete when every Files row has a received original and every received original is accounted for; valid when every hash was verified at intake, at promotion and in the verification pass. The summary states both.

## 9. Problem codes
| Level | Codes |
|---|---|
| **BATCH** (nothing proceeds) | `SOURCE_MISSING`, `SOURCE_KIND_MIXED`, `NOT_UTF8`, `DELIMITER_UNKNOWN`, `HEADER_MISSING`, `COLUMN_UNKNOWN`, `COLUMN_DUPLICATE`, `COLUMN_REQUIRED`, `SHEET_MISSING`, `SHEET_UNKNOWN`, `TOO_MANY_ROWS`, `TOO_MANY_COLUMNS`, `VALUE_TOO_LONG_FOR_FILE`, `MALFORMED`, `PARSE_TIMEOUT`, `TOO_MANY_PROBLEMS` (over 250,000), `DRY_RUN_FAILED` |
| **ERROR** (row rejected) | `VALUE_REQUIRED`, `VALUE_TOO_LONG`, `SUBJECT_TOO_LONG`, `DATE_INVALID`, `DATE_ORDER`, `DATE_FUTURE`, `MAPPING_UNCONFIRMED`, `STATUS_UNMAPPED`, `IN_FLIGHT_NOT_IMPORTED`, `TYPE_UNMAPPED`, `UNIT_UNMAPPED`, `CLASSIFICATION_UNMAPPED`, `CLASSIFICATION_BLANK`, `FIELD_INVALID`, `NUMBER_TOO_LONG`, `NUMBER_DUPLICATE_IN_BATCH`, `NUMBER_IN_USE`, `NUMBER_IN_LIVE_SERIES`, `ALREADY_IMPORTED_CHANGED`, `MAIN_FILE_MISSING`, `MAIN_FILE_MULTIPLE`, `TOO_MANY_FILES`, `TOO_MANY_APPROVALS`, `APPROVAL_ORDER_DUPLICATE`, `FILE_NOT_RECEIVED`, `FILE_AMBIGUOUS`, `FILE_REFUSED`, `FILE_EMPTY`, `FILE_HASH_MISMATCH`, `FILE_CLASSIFICATION_LOWER`, `FORMULA_NO_VALUE`, `ORPHAN_ROW` (a Files or Approvals row whose legacy number has no Memos row; reported on that row) |
| **WARNING** (imported, listed) | `PERSON_NOT_LINKED`, `PATH_CASE_MATCH`, `FORMULA_CACHED`, `CLASSIFICATION_DEFAULTED`, `NUMBER_NOT_TEXT`, `NUMBER_SIMILAR`, `FILE_SHARED` |
| **FILE** (listed) | `FILE_UNREFERENCED`, `PATH_CASE_ONLY`, `FILE_REFUSED` (with the pipeline's reason, for a file no row uses) |
| **INFO** | `ALREADY_IMPORTED` (skipped) |
| **Import and verify stages** | `IMPORT_ERROR`, `FIXITY_MISMATCH`, and any ERROR code found again at import time |

- At most 20 problems per row. Messages are built from `code` and `params` in the web (English and Arabic) and in the report (English). `params` hold column names, values the admin typed (a status, a path), counts and limits, never subjects or remarks.

## 10. API and authorisation
All paths are under `/api/v1`. Bodies are JSON. Errors are RFC 9457 Problem Details with a `code`. Mutations take `If-Match` on the batch version (412 when stale) and `Idempotency-Key` where noted.

| Method and path | Who | Request | Response | Errors |
|---|---|---|---|---|
| `GET /admin/imports?cursor=` | `memo.import` | — | batches, newest first (name, source code, state, counts, who, when) | 403 |
| `POST /admin/imports` | `memo.import` (Idempotency-Key) | `{name, sourceCode, timeZone, defaultClassificationId?, numberPrefix?}` | `BatchView` | 422 `SOURCE_CODE_INVALID`, `TIME_ZONE_INVALID`; 409 `TOO_MANY_BATCHES` |
| `GET /admin/imports/{id}` | `memo.import` | — | `BatchView`: settings, state, sources, intake counts by state, problem counts by level, row counts by result, progress (rows done, rate, estimated end, throttled), report summary, `allowedActions` | 404 |
| `PUT /admin/imports/{id}` | `memo.import` | settings as in POST | `BatchView` (back to OPEN) | 409 `WRONG_STATE` |
| `GET /admin/imports/template` | `memo.import` | — | the .xlsx (attachment) | — |
| `POST /files?purpose=imports.source&owner={id}:{table}` | `memo.import`; batch OPEN or VALIDATED | raw body | file version (existing upload API) | 422 pipeline reasons; 409 `WRONG_STATE` |
| `POST /admin/imports/{id}/files` | `memo.import` | `{files: [{path, size}]}` (1–1,000) | `[{path, intakeId, state, size}]` | 422 `PATH_INVALID`, `PATH_DUPLICATE`, `LIMIT_EXCEEDED` (200,000 originals or the batch byte cap) |
| `GET /admin/imports/{id}/files?state=&cursor=&limit=` | `memo.import` | — | intake entries (limit ≤ 5,000) | — |
| `POST /files?purpose=imports.intake&owner={intakeId}` | `memo.import`; entry EXPECTED or RECEIVED, batch OPEN or VALIDATED | raw body | file version | 422 pipeline reasons; 429 |
| `POST /admin/imports/{id}/dry-run` | `memo.import` | — | 202 `BatchView` (DRY_RUN) | 409 `WRONG_STATE`, `SOURCE_MISSING` |
| `GET /admin/imports/{id}/problems?level=&code=&sheet=&cursor=` | `memo.import` | — | problems (100 a page) and counts by level and code | — |
| `GET /admin/imports/{id}/rows?result=&cursor=` | `memo.import` | — | rows (legacy number, result, codes, memo id; the subject only within the viewer's clearance, else "Restricted") | — |
| `GET /admin/imports/{id}/mappings` | `memo.import` | — | per kind: legacy value, rows affected, legacy order, proposal, target, confirmed | — |
| `PUT /admin/imports/{id}/mappings/{kind}` | `memo.import` | `[{legacyValue, target, legacyOrder?, confirmed}]` | mappings; batch back to OPEN | 422 `MAPPING_INVALID`, `CLASSIFICATION_ORDER`; 409 `WRONG_STATE` |
| `POST /admin/imports/{id}/start` | `memo.import` + **step-up** | `{expectedRows}` | 202 `BatchView` (IMPORTING) | 409 `WRONG_STATE`, `ROWS_CHANGED`, `ANOTHER_IMPORT_RUNNING`; 422 `NOTHING_TO_IMPORT`, `BATCH_PROBLEMS`, `IMPORTER_NOT_CLEARED` |
| `POST /admin/imports/{id}/stop` | `memo.import` | — | `BatchView` (STOPPING) | 409 |
| `POST /admin/imports/{id}/resume` | `memo.import` + **step-up** | — | `BatchView` (IMPORTING) | 409 |
| `POST /admin/imports/{id}/finish` | `memo.import` | — | `BatchView` (VERIFYING) | 409 |
| `POST /admin/imports/{id}/close` | `memo.import` | `{reason?}` | `BatchView` (CLOSED or ABANDONED) | 409 |
| `GET /admin/imports/{id}/report` | `memo.import` or `audit.read`, cleared to `max_rank` | — | `{state, balanced, complete, valid, totals, parts: [{kind, fileId, versionId, sha256, size}]}`; parts download through `GET /files/{fileId}/versions/{versionId}/content` | 403 (not cleared), 404 |
| `GET /memos/{id}` | readers (ADR-0050) | — | `MemoView` + `origin`, `legacy` (§11) | 404 |
| `POST /memos/{id}/legacy/corrections` | readers with `memo.import` + **step-up** | `{field, value, reason}` | `MemoView` | 409 `NOT_IMPORTED`; 422 |
| `POST /memos/{id}/legacy/reclassify` | readers with `memo.import` + **step-up**, cleared to the new level | `{classificationId, reason}` | `MemoView` | 422 `CLASSIFICATION_NOT_HIGHER` |
| `POST /search` | signed in (ADR-0054) | adds `origin: ["IMPORTED" \| "NATIVE"]` | results with `origin` | as today |

- **`memo.import`** (new): privileged, not scopable, in `GRANTED_ON_PURPOSE` (no built-in role carries it; the demo grants it through a role to the tenant admin). Holders count as administrators for the MFA policy (ADR-0024).
- **Matrix rule:** every `/admin/imports` endpoint is 403 for every subject without `memo.import`; start and resume need the step-up (`tenantAdminStepUp`); the report needs clearance.
- **The importer reads no memo by importing it.** `GET /memos/{id}` stays under ADR-0050.
- **Purposes:**

| Purpose | Owner | Storage | Types | Max | Upload by | Read by | Renditions |
|---|---|---|---|---|---|---|---|
| `imports.source` | `{batchId}:{table}` | ORDINARY, purgeable | XLSX, TEXT (csv) | 20 MiB | `memo.import`, batch OPEN or VALIDATED | `memo.import` | — |
| `imports.intake` | intake id | ORDINARY, purgeable, legacy formats | as `memo.legacy` | 100 MiB | `memo.import`, entry and batch state | `memo.import` | — |
| `imports.record` | batch id | RECORD | PDF, XLSX, TEXT | 1 GiB | `System("legacy-import")` | `memo.import` or `audit.read`, cleared to `max_rank` | — |
| `memo.legacy` | memo id | RECORD, legacy formats | PDF, PNG, JPEG, WEBP, TIFF, TEXT, ZIP, DOCX, XLSX, PPTX, ODT, ODS, ODP, DOC, XLS, PPT, RTF | 100 MiB | `System("legacy-import")` | readers of the memo cleared to the file's effective level | PDF, TEXT |

- `memo.legacy` retention: the memo type's retention years when set (whatever it counts from), else the tenant's default, counted from the import. This is never shorter than counting from the legacy dates; Phase 7's disposition reads the legacy dates.
- `inline` display for PDF and images, as for attachments.

## 11. Imported memos across the product
- **`MemoView`:**
  - `origin` (`NATIVE` or `IMPORTED`);
  - `initiator` becomes optional (null for an unlinked imported initiator). This is an OpenAPI change; the web client is regenerated in the same task;
  - `legacy`: source code, batch name, legacy number as given, `importedAt`; dates as given (`raisedOn`, `decidedOn`, `closedOn`); status, type, unit and classification as given; the initiator as given with the linked user if any; the approval history (ordinal, name, linked user, position, decision, date, remark); the originals (role, title, type, size, classification, readable or "Restricted original", renditions; SHA-256 for auditors); the corrections;
  - `allowedActions` on an imported memo: `CORRECT_LEGACY` and `RECLASSIFY` for holders of `memo.import`; share and copy-to as for any approved memo (ADR-0050); watch. Nothing else: no route, workflow, note, close, link from it, edit or final record.
- **Lists and boxes:** the initiator's Drafts, Sent and Closed boxes filter `origin = 'NATIVE'`; imported memos are found by search. They show an "Imported" badge wherever they appear (search results, links, related files).
- **Search (ADR-0054):**
  - `MemoSearchFacts` gains `origin`, a nullable `initiatorId`, and for imported memos `createdAt` = the start of `raised_on` in the batch's time zone, so the date filter finds them by when they were raised;
  - `fieldsText` adds the legacy number as given, the initiator's and approvers' names, the legacy unit and type, so people who left can be found by name;
  - `MemoSearchSource.files` includes `memo_legacy_file` originals at their effective rank;
  - the Search page gets an **Origin** filter (Created here, Imported) and a chip.
- **Dashboards (E05):** throughput's "created" counts `origin = 'NATIVE'` only. Cycle time and pending come from workflow instances and tasks, which imported memos do not have.
- **Watching (E05):** no auto-watch on import; nothing notifies. A reader may follow an imported memo by hand; nothing will happen to it except corrections, which publish no watch news.
- **Final record (E04):** none. The backfill and `MemoWorkflowSubject` skip `origin = 'IMPORTED'`. The memo page says "No final record: for an imported memo the original documents are the record."
- **Auto-close and closing:** `MemoClosing.due` filters `origin = 'NATIVE'`; imported memos cannot be closed in the system (a legacy CLOSED is imported as CLOSED).
- **Numbering register (ADR-0044):** imported numbers have no series, so they are never in the register or its gap check.
- **Fixity (ADR-0053):** `RecordFixity` also re-reads `memo_legacy_file` versions in pages of 100, auditing `memo.legacy.verified` (counts) and `memo.legacy.mismatch`. The rolling sample setting applies to both.
- **Corrections:** `CORRECT_LEGACY` appends a `memo_legacy_correction` (field, before, after, reason); `SUBJECT` also updates `memo.subject` and reindexes it. `RECLASSIFY` raises the memo's level (never lowers; the effective level of originals follows), appends a `CLASSIFICATION` correction and updates search ranks. Both audited.

## 12. Audit events
- **imports:**
  - `import.batch.created`, `import.batch.changed` (settings before and after);
  - `import.source.attached` (table, file version, SHA-256);
  - `import.mapping.changed` (kind, entries, hash, before and after);
  - `import.dry_run.requested`, `import.dry_run.finished` (counts by level and result, input hash);
  - `import.batch.started` (step-up time, rows to import, input hash, `max_rank`), `import.batch.stopped`, `import.batch.resumed`, `import.batch.finished` (counts);
  - `import.batch.verified` (originals checked, mismatches);
  - `import.batch.completed` (totals, balanced, complete, valid, each report part's SHA-256);
  - `import.batch.closed` and `import.batch.abandoned` (staged versions purged).
- **memo:**
  - `memo.imported` (per memo, in its transaction): batch, source code, legacy number, row number, row fingerprint, each original's file version and SHA-256, mapped type, status, classification and unit, the number of linked people, `startedBy`;
  - `memo.legacy.corrected` (field, before, after, reason), `memo.legacy.reclassified` (before, after, reason);
  - `memo.legacy.verified` (weekly counts), `memo.legacy.mismatch` (file version, expected and actual hashes).
- **files:** `file.version.created` and `file.upload.rejected` for every source and original (existing), `file.version.purged` (new).
- **Actor:** the jobs act as `System("legacy-import")`; `memo.imported` carries `startedBy`, so every imported memo leads to a person and a step-up.
- Reads of the batch screens are not audited (lists, ADR-0019); report downloads are (`file.opened`).

## 13. Limits and settings
| Setting | Default | Notes |
|---|---|---|
| `imports.max-open-batches` | 5 per tenant | CLOSED and ABANDONED do not count |
| `imports.max-originals` | 200,000 per batch | |
| `imports.max-batch-bytes` | 200 GiB | staged bytes; the runbook sizes the `work` bucket |
| `imports.source.max-bytes` | 20 MiB | per source file |
| Rows | 50,000 memos, 200,000 files, 250,000 approvals | per batch |
| `imports.chunk-rows` | 50 | per job run |
| `imports.rendition-backlog` | 200 | pending `memo.legacy` renditions before the job waits |
| `imports.report.max-wait` | 72 hours | before the report is built with pending renditions |
| `imports.keep-staged-days` | 30 | after COMPLETED; then closed and purged |
| `imports.idle-days` | 30 | OPEN or VALIDATED without change, then ABANDONED |
| `IMPORT_INTAKE_PER_USER` | 600 a minute | uploads of `imports.intake` |
| Parse pool | 1 thread per node, 120 s per source | `imports-parse` |

## 14. Performance budget
| Operation | Target | How |
|---|---|---|
| `GET /admin/imports/{id}` | p95 < 100 ms | counts kept in `totals` during jobs; indexed counts by state |
| Register 1,000 paths | p95 < 500 ms | one multi-row insert with `ON CONFLICT DO NOTHING` |
| Intake list page (5,000) | p95 < 300 ms | keyset on `(tenant_id, batch_id, state, path)` |
| Problem list page (100) | p95 < 150 ms at 250,000 problems | keyset on `(tenant_id, batch_id, level, code, id)` |
| Parse a 20 MiB XLSX (50,000 memos, 200,000 files) | < 120 s, heap < 512 MB | event API; batched inserts of 1,000; measured in T1 |
| Dry run, 10,000 memos and 30,000 originals | < 5 min | set-based cross-row checks; chunks of 500 |
| Import, 10,000 memos with 3 originals each (about 1.5 GB) | < 2 h on the 16 GB dev VM, renditions excluded | promotion streams; chunks of 50; measured in T10 |
| Promotion of a 5 MB original | p95 < 2 s | one pipeline pass (scan, encrypt, store) |
| Memo view of an imported memo | p95 < 150 ms | indexes on the legacy tables by memo |
| Search with the Origin filter | within ADR-0054's 300 ms | the `origin` column on each part's table |

## 15. Background work
| Job | Kind | Trigger | Retries and idempotency |
|---|---|---|---|
| `imports-dry-run` | tenant one-off, instance `{batchId}` | `POST …/dry-run` | steps resumable; staging rows replaced per batch; 3 attempts, then OPEN with `DRY_RUN_FAILED` |
| `imports-run` | tenant one-off, instance `{batchId}`, reschedules itself | start, resume | guarded row updates; promotion keys `imp-{batchId}-{rowFileId}`; row attempts ≤ 3 |
| `imports-verify` | tenant one-off, instance `{batchId}:verify` | entering VERIFYING | re-reads only; restarts from the beginning |
| `imports-report` | tenant one-off every 10 minutes while VERIFYING | the verify job | stored once; the report parts' idempotency keys are per batch |
| `imports-sweep` | platform, hourly, scheduling tenant tasks | — | returns stale IMPORTING rows to READY; abandons idle batches; closes and purges batches 30 days after COMPLETED; purges staged versions of CLOSED and ABANDONED batches in pages of 500 |

Every payload carries `tenantId` (`TenantScopedPayload`); the actor `System("legacy-import")` is bound from the task data (ADR-0018). A job that finds no tenant fails closed.

## 16. UX (summary; flows in `ux-review.md`)
- **Administration → Import** (holders of `memo.import`): a list of batches and **New batch**.
- **Batch page**, a stepper: 1 Metadata, 2 Originals, 3 Mappings, 4 Dry run, 5 Import, 6 Report. Each step says what is done and what is next; steps after a change go back to "needs a new dry run".
  - **Metadata:** download the template; upload a workbook, or CSV files per table.
  - **Originals:** choose a folder; a list of what will be sent (counts, size, skipped system files, too large); progress (files and bytes, rate, time left), pause and resume; refused files with their reason; resume after a reload by choosing the folder again.
  - **Mappings:** a tab per kind; each legacy value with its row count, proposal, target picker and Confirm; classification with the legacy order (move up and down) and the rule shown.
  - **Dry run:** totals by result; the problem list with filters (level, code, sheet) and a link from a row's problem to its sheet row number; "Download problems (XLSX)".
  - **Import:** a confirmation with the counts, the warnings and what will not happen ("no approvals, notifications or final records are created"), then the step-up; progress with Stop; "Waiting for previews to catch up" while throttled.
  - **Report:** balanced or not, complete and valid, the totals, and the downloads.
- **Memo page (imported):** a banner ("Imported from LEGACY-DMS on 16 Nov 2026. Created and decided in an earlier system; not verified by this system."), legacy details, "Legacy approval history (imported, not verified by this system)", "Original documents", corrections, and no final record box.
- **Search:** the Origin filter and an "Imported" badge.
- **i18n:** a catalogue part `imports` in English and Arabic; problem messages by code; paths, legacy numbers and hashes shown left-to-right in isolation (`<bdi dir="ltr">`) inside Arabic text. The report is English only (§10 Q3).
- **Accessibility:** the stepper is a list of links with `aria-current="step"`; progress is a `progressbar` with a polite live region updated at most every 5%; the problem table is a real table with sortable headers; nothing relies on colour alone.

## 17. Decided in planning (beyond the research)
1. **A separate module `imports`** owns batches and staging; the memo module owns what an imported memo is, behind `LegacyMemos`.
2. **Originals live in `memo_legacy_file`** (purpose `memo.legacy`), not as the memo's document and attachments: those carry editing, locking and per-round binding that a legacy memo does not have.
3. **The permission is `memo.import`** (one key, not `memo.import.manage`), granted on purpose; reports are also readable by `audit.read` holders cleared to them.
4. **The importer must be cleared** to every level the batch writes, and does not become a participant. Corrections need the permission, the memo's read rule and a step-up.
5. **Approvals are a third table** (an Approvals sheet or CSV), so CSV is three files, not two.
6. **Classification order:** the admin gives the legacy markings' order and the mapping must keep it; this is how "never less strict" is enforced, since the system cannot know legacy strictness otherwise.
7. **People are linked by email or a mapped legacy id only.** The directory has no employee numbers. A name-only person can never be linked.
8. **A legacy number held as a number** is flagged (`NUMBER_NOT_TEXT`); numbers differing only in case or separators are flagged (`NUMBER_SIMILAR`).
9. **The live-series check** looks at the period and the counter, so old legacy numbers in a matching format are allowed when the series can no longer produce them.
10. **An optional batch number prefix** for clashes that a starting number cannot fix.
11. **Dates:** `created_at` is the import time; search's date filter uses the legacy raised-on date; `closed_at` is the start of the legacy closing date only because V9's CHECK needs it; `approved_at` stays null.
12. **The dry run validates in `readInTenant` transactions**, which the T1 spike showed PostgreSQL holds read-only (`transaction_read_only` on, SQLSTATE 25006 on a write).
13. **Promotion refuses a hash mismatch before storing** (`copyFrom` with the expected SHA-256), so no locked record is left behind by a corrupted staged copy.
14. **Staged copies are purged by a new files capability** (`purged_at`; ORDINARY and `purgeable` purposes only); the files module had no deletion.
15. **Intake has its own upload rate limit** (600 a minute); the general 30 a minute would take 14 hours for 25,000 files.
16. **The report is stored once, when final** (renditions settled or 72 hours), not regenerated while renditions are pending, so no record versions pile up.
17. **TIFF and RTF** are accepted for the legacy purposes. ONLYOFFICE converts RTF but refuses TIFF (T1), so TIFF is download-only, never decoded in the backend. Outlook `.msg` and encrypted documents are refused (clamd at intake for Word and Excel; the dry run, from Tika's 422, for PowerPoint).
18. **Imported memos are excluded** from the initiator's boxes, throughput, auto-close and the final-record backfill; allowed actions are share, copy-to, follow, correct and reclassify.
19. **One running import per tenant** and at most five open batches.
20. **Staging rows are kept** after closing (the chain of custody); only staged objects are purged.

## 18. Migrations and compatibility
- **Order** (Spring Modulith runs modules in dependency order, ADR-0007): `files/V4__purge.sql` → `memo/V19__legacy.sql` → `search/V3__origin.sql` and `imports/V1__imports.sql`.
- **Expand only.** New columns have defaults (`origin = 'NATIVE'`), constraints are relaxed only for `origin = 'IMPORTED'`, and no existing row changes.
- **Rolling upgrade:** an old app version cannot read a memo with a null initiator. No such memo exists until an import runs, and imports run only on the new version (the endpoints and jobs exist only there). The runbook says: finish the rollout before the first import.
- **RLS:** every new table has `tenant_id`, RLS ENABLED and FORCED with the standard policy, `tenant_id` first in every composite index, and an index for every foreign key. `RlsMetaTest` covers them. The cross-tenant test reads, updates and deletes across tenants on every new table and endpoint.
- **jOOQ** code is regenerated (`JooqCodegenTest`).
