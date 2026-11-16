---
status: accepted
date: 2026-11-16
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0055 — Legacy import: a metadata workbook and a folder of originals, a read-only dry run, idempotent rows, legacy numbers outside the register

## Context and problem statement
A bank that adopts memo brings years of memos from an older system: the original files, and a spreadsheet of their facts (number, type, subject, dates, people, status, classification). The overview's E06 asks for a bulk import that keeps the originals as the source of truth, makes PDF renditions and extracted text, keeps the legacy numbers outside the gapless series, offers a dry run, and ends with a reconciliation report (overview §3 E06, research R11).

Legacy data is messy (overview risk R8). People have left. Statuses do not match ours. Some files are old Office formats. Spreadsheets corrupt numbers and dates when opened in Excel. And everything an import writes becomes part of the record (`AGENTS.md §3.3`), which is never deleted. What format do we accept, how do files arrive, how is a run made safe to repeat, and what does an imported memo claim to be?

## Decision drivers
- **The record is sacred.** An imported memo must not claim more than the legacy system can prove: no authenticated approvals, no invented people, no borrowed authorship.
- **Nothing written by mistake.** A dry run that can write is worse than none (SAP KBA 3782784, research R11.4).
- **Safe to repeat.** A crash, a stop or a second batch with the same rows never duplicates or silently changes a memo.
- **Hostile files** reach no parser in the backend JVM except the admin's own workbook, and every file passes the upload pipeline (ADR-0029).
- **Classification is never lowered** in transit.
- **Live users come first:** an import must not starve the converters or the scanner.
- **Configuration, not code:** every legacy value is mapped by the tenant, never by a customer-specific branch (`AGENTS.md §3.5`).
- **Fewer moving parts on-prem:** no new service, no server drop folder, no operator access to tenant files.

## Considered options
1. **XLSX or CSV metadata, a browser folder upload of the originals one file at a time, three stages (intake, read-only dry run, import), rows keyed and fingerprinted, legacy numbers in `memo.reference` outside the register.**
2. **A ZIP of everything** (workbook and originals), unpacked on the server.
3. **A server drop folder or bucket** that the backend reads.
4. **Imported memos as live workflow memos**: legacy approvals written as workflow actions, in-flight memos imported as drafts.
5. **CSV only**, or **XLSX only**.

## Decision outcome
**Option 1.**

### Format
- One batch has three tables: **Memos** (one row per memo), **Files** (one row per original) and **Approvals** (one row per legacy decision; optional).
- They come as one **XLSX workbook** with those sheets, or as **CSV files** (one per table). Both are parsed into one row model.
- **XLSX:** Apache POI 6.0.0 (already pinned for templates, ADR-0048), through its streaming event API (`XSSFReader`).
  - The workbook passes the upload pipeline first: clamd, type detection and `ZipInspector`'s limits.
  - POI then runs under the JVM-wide `ZipSecureFile` limits that ADR-0048 set (64 MiB per entry, ratio 100), on a bounded pool with a 120-second limit, behind a catch-all that maps any failure to `MALFORMED`.
  - Caps: 20 MiB per file; 50,000 memo rows, 200,000 file rows and 250,000 approval rows per batch; Excel's 32,767 characters per cell.
  - A formula cell gives its cached value with a warning; no cached value is an error.
  - Dates are real date cells (the workbook's 1900 or 1904 system) or ISO 8601 text. Nothing else is guessed. POI's streaming handler formats dates in the 1900 system only, so the reader applies the workbook's own system (T1 spike).
- **CSV:** Apache Commons CSV **1.14.1** (Apache-2.0, a new dependency).
  - RFC 4180 quoting; a header row naming our columns is required.
  - UTF-8 only, with or without a BOM, decoded strictly. Anything else is refused with "save as CSV UTF-8".
  - Comma or semicolon, detected from the header row.
  - Duplicate or unknown columns refuse the file. Lenient end-of-file and trailing-data modes stay off.
- **The template** is an .xlsx the tenant downloads, generated per tenant: every column formatted as Text, suggestion lists of the tenant's statuses, types, classifications and units, and an Instructions sheet.
- **POI's own advice** is "never parse files from untrusted sources". We accept the risk for the workbook only, because it comes from a holder of the import permission. The parser is a nightly Jazzer target with a hostile corpus. The originals are never parsed in the backend JVM.
- **Rejected:** CSV only (Excel strips leading zeros from legacy numbers on open); XLSX only (legacy systems export CSV); FastCSV (single maintainer, latest release under two weeks old); fastexcel-reader (a second XML parser); excel-streaming-reader (a wrapper we do not need).

### No archive is unpacked on the server
- The admin unpacks the archive on their desktop and picks the folder in the browser (`<input webkitdirectory>`, Baseline since 2025). The browser uploads **each file on its own** through the existing pipeline, with its relative path.
- **Why:**
  - the platform ceiling is 100 MiB per upload and clamd scans at most 110 MB of a stream, so a migration-sized archive cannot be scanned whole before encryption (`AGENTS.md §3.4`);
  - it avoids zip slip, decompression bombs, CP437 names and duplicate entries;
  - each file gets its own scan, type check, SHA-256, audit and retry.
- A ZIP that a row lists as an original is stored whole and never opened.
- A command-line uploader over the same API is the path if a customer's volume outgrows the browser. It is not built now.

### Three stages
1. **Intake.** Sources and originals arrive as files of ordinary (unlocked) staging purposes in the `work` bucket: scanned, typed, encrypted, hashed. The relative path is kept in the batch, NFC-normalised with `/` separators.
2. **Dry run.** It runs only after intake, so it checks everything: the rows, the mappings and the files' content (type, hash, size).
   - The validators are the same ones the import uses.
   - They run in `readInTenant` transactions, which reach PostgreSQL as read-only (the T1 spike: `transaction_read_only` is on, and a write fails with SQLSTATE 25006), so a business write fails by construction. A test keeps that true.
   - The dry run writes only its own staging rows and problem list, in separate transactions.
3. **Import.** Each valid row's originals are streamed from staging back through the full upload pipeline into a **record** purpose owned by the memo.
   - That means a second scan with current signatures, and a fresh SHA-256 that must equal the intake hash or the file is refused before it is stored.
   - Then the memo, its legacy facts, its file bindings and its `memo.imported` audit entry commit in one transaction.
   - Staged copies are purged when the batch is closed, and at the latest 30 days after it completes. The report keeps the path, size and hash of every file received.

### Idempotency
- **Row key:** (tenant, source code, normalised legacy number), unique on the imported memo's legacy facts.
- **Fingerprint:** SHA-256 of the canonical row, its approvals and its files' hashes, as the IETF idempotency-key draft pairs a key with a fingerprint.
- Same key and fingerprint: **skipped** as already imported. Same key, different fingerprint: a **conflict**, never an update.
- A crash leaves whole rows done or not done. The job resumes from the rows still waiting, and promotion uploads carry idempotency keys.

### What an imported memo is
- **Origin.** `memo.origin = IMPORTED` (native memos are `NATIVE`). The memo is in a final state from the start and never enters a workflow.
- **Legacy numbers** go in `memo.reference`, so display, links and search work unchanged. `number_series` stays null, so they are **outside the register and its gapless check** (ADR-0044). An optional batch prefix avoids clashes.
  - The dry run refuses a number already used, repeated in the batch, or one a published series could still produce (its format matches, its period is current or later, and its sequence is above the counter). The fix is the series' one-time starting number (ADR-0044) or a batch prefix.
- **Statuses** map through the batch's table to APPROVED, REJECTED, WITHDRAWN, CANCELLED or CLOSED. **In-flight legacy memos (draft, pending, returned) are refused**: the bank finishes them in the old system, or raises them anew. This follows Microsoft's and ShareGate's migration guidance.
- **People stay as the text given** (name, and optional email or legacy id).
  - A person is linked to a current user **only by an explicit identifier**, confirmed in the batch's people mapping: an email that equals a user's, or a legacy id the admin maps to a user. A name alone never links.
  - There are **no placeholder accounts**, and the importing admin is **never** recorded as initiator or approver.
  - So `memo.initiator_id` may be null, for imported memos only (a CHECK on `origin`).
  - Linked people become participants (INITIATOR, APPROVER) and can read the memo, always under clearance (ADR-0050).
- **Legacy approvals are history, not workflow.**
  - They are kept in their own table as given: order, name, linked user, position, decision, date and remark.
  - The memo shows them as "Legacy approval history (imported, not verified by this system)".
  - No workflow instance, action, task, stamp or notification is created.
- **No final record (PDF/A) for imported memos.** ADR-0053's record presents our note sheet and authenticated approval stamps (ADR-0051). Built from unverified legacy facts it would look the same as an authenticated approval. **The originals are the record.** They get PDF and text renditions (ADR-0045) for preview and search, and the weekly fixity job (ADR-0053) re-hashes them.
- **Classification** maps each legacy value explicitly to a tenant level.
  - The admin gives the legacy values' order, and the mapping must keep it: a stricter legacy marking never maps to a lower level than a less strict one.
  - An unknown value is an error. A blank is an error unless the batch names a default level, which the report states.
  - A file's level may only be stricter than its memo's (ADR-0046). After import, a memo's level may only be raised.
- **Corrections after import** are appended entries with a reason, audited. The legacy facts as given are never overwritten.
- **Imported memos are excluded** from approval-time figures and throughput on dashboards, auto-close, the final-record backfill and the initiator's boxes. Search includes them, with an Imported filter. The import neither auto-watches nor notifies anyone.

### Legacy Office files, for the legacy purposes only
- **Amends ADR-0029 and ADR-0046** for two purposes only: the intake staging purpose and the imported originals' record purpose. Elsewhere, OLE2 stays refused.
- **Allowed there:** `.doc`, `.xls` and `.ppt` (OLE2), plus `.rtf` and `.tif`/`.tiff` scans.
- **Why:** a legacy archive without .doc files is unrealistic, and originals must be kept as received. ADR-0029 refused OLE2 because macros could not be checked. Now they can, without parsing them in the backend:
  - **Macros:** clamd's `AlertOLE2Macros=yes` (already set) flags VBA in Word, Excel and PowerPoint files as `Heuristics.OLE2.ContainsMacros.VBA` (T1 spike), so such files are refused as `MALWARE`, with the signature in the report. Macro-enabled OOXML stays refused.
  - **Subtype:** the in-process detector only sees a generic OLE2 container. For these two purposes the pipeline asks the isolated Tika server's `PUT /detect` (Tika 4.2 has no `/detect/stream`), which names Word, Excel or PowerPoint; the server config enables the `detect` endpoint beside `tika` (ADR-0045's list grows by one). Anything else, such as Outlook `.msg` (`application/vnd.ms-outlook`), is refused. If the Tika server is down, the upload fails closed.
  - **Encrypted documents:** refused, as everywhere (ADR-0029). clamd flags encrypted Word and Excel files (`Heuristics.Encrypted.OLE2`) at intake. A CryptoAPI-encrypted PowerPoint file passes clamd (T1 spike); its text extraction is refused (Tika 422) and so is its conversion (ONLYOFFICE -5), so the dry run reports the file `UNREADABLE` and its row cannot be imported until the file is replaced.
- **Renditions:** ONLYOFFICE converts doc, xls, ppt and rtf to PDF (about 1 s each in the T1 spike). It refuses TIFF (-7), so **TIFF scans are download-only** in E06; a TIFF rendition from an isolated converter is carried. Nothing decodes these formats in the backend JVM.
- A purpose opts in with a flag that only these two purposes may set; an architecture test checks it.

### Who
- **A new permission, `memo.import`:** privileged, not scopable, granted on purpose (it is in no built-in role, like `memo.read.unit`).
- **Starting or resuming an import needs a fresh step-up.**
- The importer must be cleared for every level the batch writes. The importer does not become a participant of the memos they import.
- **No maker-checker.** The dry run, the report and the per-memo audit carry the assurance. A rehearsal on a non-production tenant is the runbook's first step (TNA: "multiple test and pilot migrations").

### The reconciliation report
- It is kept with the batch as records, and the `import.batch.completed` audit entry carries their SHA-256s. It has three parts:
  - a PDF summary drawn through ADR-0052's renderer;
  - an XLSX of rows, files, problems and mappings, every value a string cell, with values that start with `=`, `+`, `-`, `@`, tab or CR (and their full-width forms) prefixed by `'` (OWASP);
  - a BagIt-style `manifest-sha256.txt`.
- The sources used are kept as records beside it (chain of custody).
- It must **balance**: rows read = imported + rejected + skipped + failed + not started; files received = imported + held back + unreferenced + refused. Otherwise it says UNBALANCED.
- A verification pass re-reads every stored original and compares its hash first. The report is stored once, when the batch's renditions have settled.
- It is classified at the highest level in the batch.

### Consequences
- Good: imported memos claim only what the legacy data says. Authorship, approvals and classification are never invented or lowered.
- Good: every original gets the same scanning, encryption, fixity and audit as a native file. No archive parser and no server-side extraction exist.
- Good: a run can stop, crash and resume, or be repeated in a later batch, without duplicates.
- Bad: two scans per original (intake and promotion), and staged copies occupy the `work` bucket until the batch is closed.
- Bad: the admin must unpack archives and pick folders; very large migrations need the command-line uploader (not built).
- Bad: POI parses an admin-supplied workbook inside the backend JVM. Bounded by the pipeline, the limits, the pool, the timeout and fuzzing; a separate process is not justified for an admin file.
- Bad: legacy Office formats are now accepted in two purposes. They are scanned for macros, typed by the isolated Tika server and converted only by ONLYOFFICE.
- Neutral: imported memos have no final record. Their originals and renditions stand in its place, and the memo view says so.

### Confirmation
- `DryRunIT`: a dry run leaves every business table unchanged, and a write inside its transaction fails with SQLSTATE 25006.
- `ImportRunIT`: a crash mid-chunk, a stop and a resume import every row exactly once; a repeated row is skipped; a changed row is a conflict.
- `ReconciliationIT`: the report balances, and a tampered staged file, a missing file and a refused file each unbalance it in the right line.
- The hostile corpus extended to XLSX, CSV and OLE2 (macros, `.msg`, encrypted, XXE, zip bomb, duplicate entries, 1904 dates, invalid UTF-8, a giant field), and the Jazzer target `ImportSheetFuzzTest`, nightly.
- An architecture test: only the two legacy purposes accept legacy formats.
- Matrix `p3-e06-imports.yaml` (permission, step-up, cleared report reads).

## Pros and cons of the options
### Option 1 — workbook or CSV, per-file folder upload, three stages
- Good: reuses the pipeline exactly; validates everything before writing; resumable per file and per row.
- Bad: two scans per file; staged copies to purge.
### Option 2 — a ZIP unpacked on the server
- Good: one upload.
- Bad: cannot be scanned whole before encryption at migration sizes; zip slip, bombs and name-encoding hazards; an extraction directory of plaintext.
### Option 3 — a server drop folder or bucket
- Good: no browser limits.
- Bad: needs operator file access to tenant data (`AGENTS.md §3.4`); impossible on SaaS; a second intake path to secure.
### Option 4 — imported memos as live workflow memos
- Good: imported memos look like native ones.
- Bad: legacy approvals would pose as authenticated ones; in-flight memos would get a second number and a route their approvers never saw.
### Option 5 — one format only
- Good: one parser.
- Bad: CSV alone invites Excel corruption of numbers and dates; XLSX alone burdens legacy exports.

## Amendment — 2026-11-16 (E06 T1 spike)
- The dry run relies on `readInTenant`, which PostgreSQL already holds read-only; no explicit `SET TRANSACTION READ ONLY` is needed.
- The Tika server's detection is `PUT /detect`, enabled in its config.
- clamd's macro signature is `Heuristics.OLE2.ContainsMacros.VBA`; encrypted Word and Excel files are `Heuristics.Encrypted.OLE2`; encrypted PowerPoint files are caught by the dry run.
- TIFF scans are download-only; ONLYOFFICE does not convert them.
- The streaming reader applies the workbook's date system itself.

## Amendment — 2026-11-16 (E06 T8, the report)
- **Encrypted PowerPoint files are reported from their failed renditions, not refused by the dry run.** This replaces "the dry run reports the file `UNREADABLE` and its row cannot be imported" (Decision, encrypted documents) and the T1 amendment's "caught by the dry run". Nothing reads a staged original before promotion: intake versions have no renditions (a purgeable purpose may have none), and no Office file is parsed in the backend JVM. So such a file passes intake and the dry run, is imported with its row, and its PDF and TEXT renditions end FAILED (ONLYOFFICE -5, Tika 422).
- **The reconciliation report names them:** the summary PDF's "Renditions failed" section and the workbook's Files sheet list every imported original whose rendition failed, by path and kind, and the renditions line counts them. The original stays the memo's record (download only); the admin replaces it through a later batch or a correction if a readable copy exists. The memo is still found by its metadata.
- **The report's balance lines,** as built: rows; Files rows (matched, not received, ambiguous); originals registered (stored, refused, never uploaded) and stored (imported, held back, unreferenced); each rendition kind (done, failed, not possible, pending, not needed: a PDF or a picture is its own preview); and fixity. "Files received = imported + held back + unreferenced + refused" in the Decision is split into the two originals lines.
- **The sources are kept as records when the report is stored**, not at start: they cannot change after the dry run (the batch is no longer editable) and are purged only when the batch is closed, after the report.

## More information
- Research: `docs/plan/phase-3-memo-efile/epic-06-legacy-import/research.md` (R11, R1). Design: `epic-06-legacy-import/design.md`.
- Apache POI security: https://poi.apache.org/security.html (accessed 2026-11-16).
- Apache Commons CSV: https://commons.apache.org/proper/commons-csv/ (accessed 2026-11-16).
- RFC 8493 (BagIt): https://www.rfc-editor.org/rfc/rfc8493 (accessed 2026-11-16).
- IETF idempotency-key draft: https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/ (accessed 2026-11-16).
- OWASP CSV injection: https://community.owasp.org/attacks/CSV_Injection (accessed 2026-11-16).
- Microsoft, in-progress workflows in migration: https://learn.microsoft.com/en-us/sharepointmigration/migration-assessment-scan-workflow-running-solutions-2010 (accessed 2026-11-16).
- Related: ADR-0019 (audit), ADR-0029 (uploads; amended), ADR-0044 (numbering), ADR-0045 (renditions), ADR-0046 (attachments; amended), ADR-0048 (POI and its limits), ADR-0050 (access), ADR-0051 (authenticated approvals), ADR-0052 (generated pages), ADR-0053 (final record and fixity), ADR-0054 (search).
