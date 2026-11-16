# E06 Legacy import — research

> Step 1 of `AGENTS.md §5`. Overview research item **R11** (legacy import: spreadsheet formats, mapping legacy statuses and people who have left, preserving originals, reconciliation reports in records migrations), and R1 for everything E06 adds. Gathered 2026-11-16 by a research agent and checked against the code on `master` (E01–E05 merged or in review). All sources accessed **2026-11-16**. "Unconfirmed" marks anything not verified against a primary source. There is no pilot bank (overview §10 Q2), so every sample is synthetic.

## Lead's checks and conclusions

- **The shape of an import.** One import **batch** is:
  - a metadata workbook with two sheets, **Memos** (one row per memo) and **Files** (one row per file: legacy number, relative path, role main or attachment, title, optional SHA-256, optional classification);
  - or the same two tables as two CSV files;
  - and the **originals**, uploaded from a folder the admin picks in the browser, **one file at a time through the existing upload pipeline** (ADR-0029).
  - The downloadable template is an .xlsx with every column formatted as Text, drop-down lists for status and classification, and an Instructions sheet.
- **Formats: accept XLSX and CSV, parse both into one row model.**
  - **XLSX** with Apache POI 6.0.0's streaming event API (`XSSFReader`). POI is already on the classpath for templates (ADR-0048), so nothing new is linked.
    - The file first passes the upload pipeline: clamd, type detection, and `ZipInspector`'s bomb limits.
    - Then POI runs under the lowered `ZipSecureFile` limits ADR-0048 already sets, with a time limit, a 20 MiB file cap, and caps on rows (50,000 memos and 200,000 files per batch) and cell length (Excel's own 32,767).
    - Formula cells: the cached value is used, with a warning; no cached value is an error.
    - Dates: real date cells in the 1900 or 1904 system, or text in ISO 8601 (`2019-03-14`). Nothing else is guessed.
  - **CSV** with **Apache Commons CSV 1.14.1** (Apache-2.0, 2025-07-27, repository active in November 2026). Its dependencies, Commons IO and Commons Codec, are already in the lockfile at newer versions.
    - RFC 4180 quoting.
    - **UTF-8 only**, with or without a BOM (Commons IO `BOMInputStream`), decoded strictly. Invalid UTF-8 rejects the file with "save as CSV UTF-8".
    - Comma or semicolon, detected from the header row, which must name our columns.
    - `setMaxRows` and the 20 MiB cap bound memory.
  - **Rejected:**
    - **CSV only:** Excel strips leading zeros and reformats dates on open, so people would corrupt legacy numbers before uploading.
    - **XLSX only:** legacy systems export CSV most easily.
    - **FastCSV:** 4.4.1 is under two weeks old, and it has a single maintainer on the parsing path.
    - **fastexcel-reader 0.20.2:** it brings a second XML parser (Aalto) when POI is already here.
    - **excel-streaming-reader 6.0.0:** a wrapper over POI we don't need.
    - **Converting XLSX to CSV through ONLYOFFICE:** supported, but which sheet it converts is undocumented.
- **POI itself says "never parse files from untrusted sources".**
  - We accept the risk only because the workbook comes from a tenant admin with an import permission and a fresh step-up. The precedent is ADR-0048 (admin templates).
  - It runs on a bounded executor with a timeout and a broad catch.
  - The parser is the Jazzer target the overview already names, run nightly.
  - Its hostile corpus covers: XXE and DTDs in `sharedStrings.xml`, a zip bomb, duplicate zip entry names (CVE-2025-31672), a huge shared-strings table, the 1904 date system, invalid UTF-8, a giant CSV field, and every BOM variant.
- **Archives: the server never unpacks a ZIP.** The admin extracts the archive on their desktop and picks the folder. The browser sends each file as its own upload. Reasons:
  - **Platform limits:** the platform ceiling is 100 MiB per upload, and clamd scans at most 110 MB of a stream. A migration-sized ZIP could not be scanned whole, which breaks "scan before encryption" (AGENTS §3.4).
  - **ZIP hazards avoided:** zip slip, decompression bombs (a 10 MB file can expand to 281 TB), CP437 versus UTF-8 names, duplicate entry names, and local headers that disagree with the central directory.
  - **Benefits:** each file gets the pipeline's own scan, type detection, SHA-256 and audit, and retries one at a time.
  - **Nested archives:** a ZIP that a row lists as an original is stored whole as an original, checked by `ZipInspector`, and never opened.
  - **Rejected alternatives:** server-side extraction, for the reasons above; a server drop folder or bucket, because it needs operator file access to tenant data (AGENTS §3.4) and cannot work on SaaS. A command-line uploader over the same API is the path if a customer's volume outgrows the browser. It is not built in E06.
  - **Browser support:** `<input webkitdirectory>` has been Baseline since August 2025 and gives each file's relative path.
- **Stage 1, intake.** Files go to a staging purpose in the `work` bucket: scanned, typed, encrypted, SHA-256 recorded.
  - The relative path is stored in the batch's intake table, because file names in the pipeline drop path parts (`FileNames`).
  - Paths are NFC-normalised with `/` separators (RFC 8493 §6.1.1).
  - Resuming: the client re-lists the folder and skips paths the server already holds at the same size.
- **Stage 2, the dry run** runs only after intake, so it can validate **everything**, file content included. This is what Alfresco's dry run cannot do: it never validates metadata, because validation happens only on write.
  - It runs the **same validators as the import** in a `READ ONLY` transaction, so a business write fails by construction. SAP KBA 3782784 records a migration "simulation" that wrote data.
  - It writes only its own staging rows and problem list, in a separate transaction.
- **Stage 3, the import.** Each row is promoted by streaming its staged plaintext back through the normal upload pipeline (`Uploads.upload` takes an `InputStream`), into a **record** purpose owned by the memo.
  - That means a second scan with current signatures, a fresh SHA-256 that must equal the intake hash (fixity in transit), and the record lock.
  - A ciphertext copy is impossible anyway: the associated data binds a ciphertext to its file and version (ADR-0028).
  - Staged copies are deleted when the batch is closed (default 30 days after). The report keeps the path, size and hash of every file received, imported or not.
- **Legacy Office files (OLE2: .doc, .xls, .ppt) are allowed for the legacy purpose only.** This relaxes ADR-0029 and ADR-0046 for that one purpose, and ADR-IMPORT records it.
  - A legacy archive without .doc files is unrealistic, and the originals must be kept as received.
  - The reason they were refused was "no way to check macros without POI". Now:
    - clamd's `AlertOLE2Macros=yes` (already in `deploy/clamav/clamd.env`) flags VBA as `Heuristics.OLE2.ContainsMacros`;
    - the isolated Tika server's `/detect/stream` names the exact type;
    - ONLYOFFICE converts doc, xls and ppt to PDF.
  - Macro-enabled files stay refused.
  - TIFF scans are not a type we detect yet. The design decides between adding TIFF with a PDFBox image-to-PDF rendition and asking for PDFs.
- **Renditions and text come from the existing pipeline (ADR-0045)**, through `FilePurpose.renditions` on the legacy purpose.
  - The import throttles itself: it reschedules while the batch's pending renditions exceed a bound (about 200), so live users' conversions are not stuck behind a migration backlog.
  - db-scheduler's priority option needs a schema change and an index; it is not used.
  - **No E04 final record (PDF/A) is built for imported memos.** That record presents our note sheet and authenticated approval stamps. Building one from unverified legacy facts would make them look the same as authenticated approvals. The originals are the record.
  - The weekly fixity job (ADR-0053) is extended to imported originals.
- **Legacy numbers.**
  - Stored in `memo.reference`, so display, links and E05's normalised reference search work unchanged.
  - `number_series` is null, so they stay **outside the register and its gapless check** (ADR-0044).
  - A new `memo.origin = IMPORTED` marks them.
  - Uniqueness: a unique index on (tenant, source code, normalised legacy number) for re-runs; the existing `(tenant_id, reference)` unique index across everything; and in the dry run, within the batch and against existing references.
  - Normalisation: trim, collapse inner spaces, NFC, at most 100 characters (the existing check). Numbers that differ only in case are flagged.
  - **Collision with a live series:** the dry run renders each published series' format as a pattern. A legacy number that a series could still produce is an error. The fixes are:
    - set the series' starting number past the legacy maximum (ADR-0044 allows this once, before the period's first number);
    - or give the batch a legacy prefix.
- **Statuses.**
  - Final legacy states map, through a mapping table in the batch, to APPROVED, REJECTED, WITHDRAWN, CANCELLED or CLOSED.
  - **In-flight memos (draft, in review, returned) are refused.** The bank finishes them in the old system before cut-over, or raises them anew. This matches Microsoft's own migration guidance ("in progress workflow information isn't migrated … finish the workflow before migration").
  - Importing them as live drafts is recorded as rejected: two numbers per memo, and a route the legacy approvers never saw.
- **People.** Legacy people stay as the text given (name, and optional legacy id, email or employee number).
  - A person is linked to a current user only by an explicit identifier, through a people mapping the dry run shows. A display name alone is never enough.
  - **No placeholder accounts:** they are identities nobody owns, could be reactivated, clutter the people pickers and invent data.
  - **Never the importer's account**, which is what some migration tools fall back to (Quest, unconfirmed). That would falsify authorship.
  - So `memo.initiator_id` becomes nullable **only** for imported memos (a CHECK on `origin`).
  - Linked people become participants (INITIATOR or APPROVER) and can read the memo, always under clearance (ADR-0050).
- **Legacy approvers are historical facts, not workflow.** They go into a legacy approval table: order, name text, linked user, position text, decision, date, remark. The memo view labels it "Legacy approval history (imported, not verified by this system)".
  - No `workflow_action` rows, tasks, stamps or notifications.
  - The workflow CHECK (`status ∉ {DRAFT, DISCARDED} ⇒ workflow_instance_id IS NOT NULL`) applies to native memos only.
  - Dashboards (E05) exclude imported memos from approval-time figures.
  - Search includes them, with an "Imported" filter.
  - The import neither auto-watches (`MemoStore` auto-watches the initiator today) nor notifies.
- **Classification, units, types and dates.**
  - **Classification:** each legacy value maps explicitly to a tenant level, through a mapping table the dry run shows. An unknown value is an error. **No mapping may be less strict than the legacy marking** (TNA warns sensitivity markings get lost in migrations). A blank is an error unless the batch names a default level, which the report states.
  - **Units:** legacy units map by code to the current (successor) unit, keeping the legacy name as text. The origin unit is required, because it drives `memo.read.unit`.
  - **Types:** legacy types map to existing memo types. Metadata fields are filled where mapped; required-field rules do not apply, because they run only at submit (`MemoMetadata`).
  - **Dates:** legacy dates (raised, decided, closed) are kept as given, date-only allowed, in the batch's time zone. `created_at` is the import time (TNA: dates are "particularly vulnerable to change").
  - **Corrections after import:** classification may only be raised, by an admin with a reason. Other legacy fields are corrected by appended, audited entries, never overwritten.
- **Idempotency and resuming.**
  - Row key: (tenant, source code, normalised legacy number).
  - **Fingerprint:** SHA-256 of the canonical row plus its files' hashes (the IETF draft's "idempotency fingerprint").
  - Same key and fingerprint: skipped as "already imported". Same key, different fingerprint: a conflict, never an update.
  - Each row commits in one transaction: memo, legacy parties, file bindings and the `memo.imported` audit entry, with the row hash and file SHA-256s. A crash leaves whole rows done or not done.
  - The job is a tenant one-off (`Jobs.tenantTask`, tenant id in the payload) that works in chunks and reschedules itself.
  - The admin chooses to import the valid rows. Rejected rows go to a later batch after fixing, and the key makes that safe.
- **The reconciliation report** is kept with the batch as **records**, and the `memo.import.completed` audit entry carries their SHA-256s. It has:
  - a PDF summary drawn through ADR-0052's `PageRenderer` (it names this report);
  - an XLSX of rows and files, with every value a string cell and values starting `= + - @`, tab or CR prefixed as OWASP advises;
  - a BagIt-style `manifest-sha256.txt`.
  - The workbook and the intake list are kept as records beside it (chain of custody).
  - It must **balance**: rows read = imported + rejected + skipped; files received = imported + unreferenced + rejected. If not, it says UNBALANCED.
  - It ends with a **verification pass** that re-reads every stored original's hash, after AWS DMS's "compare each row … report mismatches".
- **Who:** a new `memo.import.manage` permission. A fresh step-up is required to start a batch's import. No maker-checker: the dry run, the report and the per-memo audit carry the assurance. Rehearse on a non-production tenant first; TNA advises "multiple test and pilot migrations".
- **Versions:** everything E06 links is already pinned except Commons CSV 1.14.1. Nothing adopted is younger than two weeks. FastCSV 4.4.1 (2026-11-08) is, and is rejected anyway.
- **Questions for the owner: none blocking.** One for information: legacy OLE2 Office files become allowed for the import purpose only (above). ADR-IMPORT records it as a narrow relaxation of ADR-0029.

---

## R1: Components and versions

| Component | Version (date) | Licence | Use in E06 | Status |
|---|---|---|---|---|
| Apache POI (`poi-ooxml`) | 6.0.0 (2026-10-19), already pinned | Apache-2.0 | Read XLSX through the event API; write the report through SXSSF | Adopted (no change). OSV lists no advisory for 6.0.0 |
| Apache Commons CSV | 1.14.1 (2025-07-27); 1.14.2 unreleased | Apache-2.0 | Read CSV | **New dependency.** Commits on master to 2026-11-12. Needs Commons IO and Codec, already present (2.22.0, 1.23.0). Java 8+. No OSV advisory |
| Commons IO | 2.22.0, already present | Apache-2.0 | `BOMInputStream` | No change |
| Apache Commons Compress | 1.28.0 (2025-07-26), already pinned | Apache-2.0 | Only the existing `ZipInspector` (ZIP originals and the XLSX container) | No change. No OSV advisory |
| FastCSV | 4.4.1 (2026-11-08, **under two weeks**); 4.4.0 (2026-07-28) | MIT | — | Rejected: single maintainer; Commons CSV is enough |
| fastexcel-reader (dhatim) | 0.20.2 (2026-06-08) | Apache-2.0 | — | Rejected: brings Aalto XML 1.4.0, a second XML parser |
| excel-streaming-reader (pjfanning) | 6.0.0 (2026-10-25) | Apache-2.0 | — | Rejected: a wrapper over POI we already use directly |
| Apache Tika server | 4.2.0, already deployed (ADR-0045) | Apache-2.0 | Text of originals; `/detect/stream` names OLE2 subtypes | No change |
| ONLYOFFICE Docs | 10.0.0.1, already deployed | AGPL-3.0 (separate service) | PDF renditions, including doc, xls, ppt and rtf | No change |
| ClamAV | 1.5.4, already deployed | GPL-2.0 (separate service) | Scans at intake and promotion; `AlertOLE2Macros=yes` already set | No change |
| db-scheduler | 16.12.0 pinned; 17.0.0 and 17.1.0 exist | Apache-2.0 | Import jobs | No change. Priority is opt-in and needs a column and an index; not used |

Sources:
- Maven Central directory listings (release dates): https://repo1.maven.org/maven2/org/apache/commons/commons-csv/, https://repo1.maven.org/maven2/org/apache/poi/poi-ooxml/, https://repo1.maven.org/maven2/org/apache/commons/commons-compress/, https://repo1.maven.org/maven2/de/siegmar/fastcsv/, https://repo1.maven.org/maven2/org/dhatim/fastexcel-reader/, https://repo1.maven.org/maven2/com/github/pjfanning/excel-streaming-reader/, https://repo1.maven.org/maven2/com/github/kagkarlsson/db-scheduler/
- POMs (licences, dependencies): https://repo1.maven.org/maven2/org/apache/commons/commons-csv/1.14.1/commons-csv-1.14.1.pom, https://repo1.maven.org/maven2/org/dhatim/fastexcel-reader/0.20.2/fastexcel-reader-0.20.2.pom, https://repo1.maven.org/maven2/de/siegmar/fastcsv/4.4.0/fastcsv-4.4.0.pom, https://repo1.maven.org/maven2/com/github/pjfanning/excel-streaming-reader/6.0.0/excel-streaming-reader-6.0.0.pom
- Commons CSV changes and activity: https://commons.apache.org/proper/commons-csv/changes.html, https://github.com/apache/commons-csv/commits/master
- Advisories: OSV API queries for the three Maven packages above returned none (https://api.osv.dev/v1/query)
- db-scheduler priority: https://github.com/kagkarlsson/db-scheduler
- ONLYOFFICE conversion tables: https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/conversion-tables/
- Tika server detector: https://cwiki.apache.org/confluence/display/TIKA/TikaServer
- ClamAV options: https://raw.githubusercontent.com/Cisco-Talos/clamav/main/etc/clamd.conf.sample
- Versions already pinned: `gradle/libs.versions.toml`, `backend/gradle.lockfile`, `docs/dependencies.md`

---

## R11.1 Spreadsheet formats and safe parsing

**CSV is a loose format.**
- RFC 4180 is Informational. It says "there is no formal specification in existence", and it notes that some programs, Excel among them, do not quote at all.
- Records end with CRLF. Fields with commas, quotes or line breaks are quoted, and quotes are doubled.
- The header line is optional (the `header` MIME parameter), and `charset` is optional.
- So we fix our own profile: a header row is required; columns are matched by name; unknown columns are a batch error; duplicate column names are a batch error. https://www.rfc-editor.org/rfc/rfc4180

**Excel quirks that change data.**
- **Encoding:** Excel opens a UTF-8 CSV correctly only when it has a BOM. Otherwise the user must import it through Data › From Text/CSV. https://support.microsoft.com/en-us/office/opening-csv-utf-8-files-correctly-in-excel-8a935af5-3416-4edd-ba7e-3dfd2bc4a032
  - Hence: accept UTF-8 with or without a BOM, and refuse anything that is not valid UTF-8. Guessing Windows-1252 is rejected, because a wrong guess silently corrupts names.
  - A user in a Q&A thread reports Excel reading a UTF-8-BOM file as ANSI (unconfirmed as general behaviour): https://learn.microsoft.com/en-us/answers/questions/1642023/excel-is-not-considering-csv-utf-8-bom-file-as-utf
- **Numbers:** Excel "automatically removes leading zeros, and converts large numbers to scientific notation", and keeps only 15 significant digits. Formatting a column as Text "will not change numbers that have already been entered". https://support.microsoft.com/en-us/office/keeping-leading-zeros-and-large-numbers-1bf7b935-36e1-4985-842f-5dfa51f85fe7
  - This is why the template is an .xlsx with Text-formatted columns, and why the instructions tell people never to open a legacy CSV export in Excel before uploading it.
- **Dates:** there are two date systems, 1900 and 1904; "the 1900 serial number is always 1,462 greater than the 1904 serial number". https://support.microsoft.com/en-us/office/date-systems-in-excel-e7fe7167-48a9-4b96-bb53-5612a800b487
  - The XLSX reader honours the workbook's flag. CSV dates must be ISO 8601.
- **Limits:** 1,048,576 rows × 16,384 columns; 32,767 characters per cell; dates from 1 January 1900 (or 1904). https://support.microsoft.com/en-us/office/excel-specifications-and-limits-1672b34d-7043-467e-8e27-269d656771c3
- **Delimiter:** in locales with a decimal comma, Excel's list separator is often a semicolon (common knowledge, unconfirmed here). We accept either, detected from the header.

**Commons CSV features we rely on.**
- `setMaxRows` (since 1.14.0).
- `setDuplicateHeaderMode` (since 1.10.0), set to refuse.
- `setAllowMissingColumnNames(false)`.
- `setTrim`.
- `setLenientEof` and `setTrailingData` (since 1.11.0, for Excel). We leave these off: a malformed file is an error, not a guess.
- Commons CSV has no field-size limit and does not strip a BOM. The 20 MiB file cap bounds memory, and Commons IO's `BOMInputStream` strips the BOM.
- https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVFormat.Builder.html

**XLSX risks and POI.**
- **POI's own security page:**
  - "We strongly recommend never parsing files from untrusted or unknown sources."
  - Expect `StackOverflowError` and many `RuntimeException`s.
  - Bound the time; memory can be "very high".
  - Use the streaming APIs for large xlsx.
  - For sensitive environments, use a separate process.
  - https://poi.apache.org/security.html
- **Our mitigations:** the admin-only source with step-up; the upload pipeline first (clamd, `ZipInspector`: at most 2,000 entries, ratio 100, 1 GiB in total); then POI under ADR-0048's `ZipSecureFile` limits (64 MiB per entry, ratio 100); a bounded executor with a timeout; a catch-all that maps any failure to `MALFORMED`. A separate process is not justified for an admin file. ADR-IMPORT records the trade-off.
- **XXE is a real class in OOXML parsing:**
  - CVE-2014-3529, "The OPC SAX setup in Apache POI before 3.10.1 allows remote attackers to read arbitrary files": https://api.osv.dev/v1/vulns/CVE-2014-3529
  - CVE-2019-12415, XXE in `XSSFExportToXml`: https://api.osv.dev/v1/vulns/CVE-2019-12415
  - Denial of service through crafted DOC, PPT and XLS before POI 3.17 (CVE-2017-12626): https://api.osv.dev/v1/vulns/CVE-2017-12626
  - The corpus includes a DTD and an external entity in `sharedStrings.xml` and in a sheet, and both must be refused.
- **Duplicate zip entry names (CVE-2025-31672):** POI before 5.4.0 let different readers see different content. 5.4.0 throws on duplicates. https://osv.dev/vulnerability/CVE-2025-31672
- **The event API:** `XSSFReader` gives "a relatively small memory footprint". All unique strings are kept in memory with shared strings, so the file cap matters. SXSSF writes large sheets with inline strings, which is how the report workbook is written. https://poi.apache.org/components/spreadsheet/how-to.html

**Formula injection on export.** The report repeats legacy subjects and names, which are untrusted text.
- OWASP lists the triggers: cells starting with `=`, `+`, `-` or `@`; tab, CR and LF; and their full-width forms.
- It advises quoting every field, prefixing a single quote, and doubling quotes, and it warns that these "may fail after Excel saves and reopens".
- https://community.owasp.org/attacks/CSV_Injection (moved from https://owasp.org/www-community/attacks/CSV_Injection)
- Decision: the detail report is XLSX with string cells, plus the prefix for values with a trigger. The fixity manifest is plain text (hash and path), never a spreadsheet. **No CSV export** of the report.

**The template.**
- Sheets: Instructions, Memos, Files, Lists (statuses, classifications, types and units as the tenant has them, for drop-downs).
- **Memos columns:** legacy number; memo type; subject; raised on; decided on; closed on; status; classification; origin unit; initiator name; initiator identifier; approvers. Approvers go in a separate Approvals sheet: legacy number, order, name, identifier, position, decision, date, remark.
- **Files columns:** legacy number, relative path, role (MAIN or ATTACHMENT), title, SHA-256 (optional), classification (optional, stricter only, ADR-0046).
- Normalised sheets avoid packing lists into one cell, and paths that contain the separator.

---

## R11.2 Archive intake, fixity and chain of custody

**Why the server does not unpack archives.**
- **Zip slip:**
  - Entries named with `../` are written outside the target directory when extraction code joins names without checking.
  - The vulnerable pattern affects tar, jar, war, cpio, apk, rar and 7z as well. Disclosed 5 June 2018.
  - https://security.snyk.io/research/zip-slip-vulnerability
- **Bombs:**
  - Non-recursive overlapping-entry bombs reach "a compression ratio of over 28 million (10 MB → 281 TB)".
  - https://www.usenix.org/conference/woot19/presentation/fifield
  - The author's page refused automated fetch.
  - ADR-0029 already found that clamd does not alert on a single compressed entry larger than its MaxFileSize.
- **Stream versus central directory:**
  - Commons Compress: the format "cannot really be parsed correctly while reading a non-seekable stream". Streamed reads can return entries that are not in the central directory, and duplicate names.
  - "If possible, you should **always** prefer `ZipFile`."
  - Names are CP437 unless the language-encoding flag says UTF-8.
  - https://commons.apache.org/proper/commons-compress/zip.html
- **Our limits:** the platform upload ceiling is 100 MiB (`memo.files.max-upload-bytes`), and clamd's `StreamMaxLength` and `MaxFileSize` are 110M (`deploy/clamav/clamd.env`). Scanning a migration-sized archive whole, before encryption, is impossible without raising both.
- **Conclusion:** extraction on the desktop is one click, and per-file upload reuses the pipeline exactly. A ZIP that is itself an original (a row's attachment) is stored whole; `ZipInspector` checks it, and nothing opens it.

**Folder upload in the browser.**
- `webkitdirectory` has been "Baseline 2025 (Newly available)", working across current browsers since August 2025.
- Each `File` has `webkitRelativePath`, relative to the chosen folder.
- Chromium before 72 behaved differently.
- https://developer.mozilla.org/en-US/docs/Web/API/HTMLInputElement/webkitdirectory

**Names and normalisation (BagIt).**
- RFC 8493 §6.1.1 warns that NFC and NFD forms of the same name can make a manifest entry fail an existence check. Implementations "SHOULD prevent" files that differ only in normalisation, and should discourage files that differ only in case.
- Paths must not reach outside the payload ("..", "/", "~user").
- So:
  - paths are compared NFC-normalised with `/` separators;
  - an exact match wins;
  - otherwise a unique case-insensitive match is accepted with a warning;
  - an ambiguous match is an error.
  - We never build a filesystem path from a name: the server has no extraction directory at all.
- https://www.rfc-editor.org/rfc/rfc8493

**Fixity and completeness.**
- **BagIt:**
  - A **complete** bag has every file the manifest lists. A **valid** bag is complete and "every checksum in every manifest has been successfully verified".
  - Manifests are `manifest-<algorithm>.txt` lines of checksum and path.
  - Tools MUST support SHA-256 and SHA-512.
  - `Payload-Oxum` (bytes and file count) is a quick completeness check, never a substitute for checksums.
  - Our report applies the same two words: **complete** (every Files row has a received file, and every received file is accounted for) and **valid** (every hash verified at intake, at promotion and in the post-import pass).
  - https://www.rfc-editor.org/rfc/rfc8493
- **NDSA Levels of Digital Preservation:**
  - Level 1 integrity: verify integrity information if it was provided with the content, and generate it if not.
  - We do both: an optional SHA-256 column, checked when present, and the pipeline's own SHA-256 for every file.
  - v2.0 (2019) wording as reproduced in https://archifau.cymru/wp-content/uploads/2026/03/1.4-NDSA-Levels-of-Preservation-1.pdf (secondary).
  - Current Levels page (V2.1, March 2026; the matrix is an image): https://ndsa.org/publications/levels-of-digital-preservation/
- **Fixity across our stages:**
  - intake hash;
  - re-hash during promotion, which must match;
  - the stored version's hash re-read in the verification pass;
  - then ADR-0053's weekly fixity job, extended to imported originals.
  - A mismatch at any stage fails the row and is listed.

**Records-management sources.**
- **ISO 15489-1:2016:**
  - defines **migration** as the "process of moving records from one system to another, while maintaining the records' authenticity, integrity, reliability and useability", and **conversion** as changing records "from one medium to another or from one format to another" (§3);
  - has a clause on conversion and migration in records-system design (§8.3.5).
  - Our originals are migrated. Renditions are conversions kept beside them, never instead of them.
  - Preview sample: https://cdn.standards.iteh.ai/samples/62542/da7503dcdf474c29b5d8a07f1f321a62/ISO-15489-1-2016.pdf
- **ISO 16175-1:2020:**
  - Applications "should be able to capture and/or import/ingest evidence" of business activity, linked to its context by metadata (§4.2.1).
  - Changes to records should be registered, with the agent, a timestamp and details (§4.2.3).
  - This is our per-memo audit entry and the appended corrections.
  - Preview sample: https://cdn.standards.iteh.ai/samples/74294/e71b9334356841149f7e1cfcdefb8208/ISO-16175-1-2020.pdf
- **ISO 13008:2022** (digital records conversion and migration process) is the dedicated standard. Its text was not available; it is listed in https://gov.mb.ca/chc/archives/gro/recordkeeping/docs/recordkeeping_standards_fact_sheet.pdf
- **MoReq2010:**
  - A record's context is "its metadata, access control information, events and related entities".
  - A transfer without context is "lossy".
  - Hence: legacy events (approvals) travel with the memo as legacy history, and the workbook and the report are kept as records.
  - https://moreq.info/faq/25-how-does-moreq-s-interoperability-work.html
- **UK National Archives, "Migrating information between records management systems" (June 2017):**
  - Exports rarely capture everything, and "Objects … can become disassociated from their metadata".
  - "Dates are particularly vulnerable to change … the date of creation may be reset to the date of migration."
  - Sensitivity markings that cannot be mapped risk sensitive information being "inappropriately handled or accessed".
  - "There is no industry standard output for EDRMS."
  - Perform "multiple test and pilot migrations", test fully after the migration, and have "roll-back" plans.
  - Keep provenance, such as "audit trails and logging".
  - Keep the source system only for a defined, limited period.
  - https://cdn.nationalarchives.gov.uk/documents/information-management/edrms.pdf, checklist https://cdn.nationalarchives.gov.uk/documents/information-management/edrm-migration-checklist.pdf (index: https://www.nationalarchives.gov.uk/information-management/manage-information/policy-process/digital-continuity/step-by-step-guidance/step-4/)
- **US NARA:**
  - The Universal ERM Requirements (v3, June 2023) group requirements by lifecycle, including Transfer and Metadata: https://www.archives.gov/records-mgmt/policy/universalermrequirements
  - The minimum transfer metadata is NARA Bulletin 2015-04, listed at https://www.archives.gov/records-mgmt/policy/metadata-compiled.html (not opened; unconfirmed detail).

**Chain of custody as we implement it:**
1. the workbook (a record, with its hash);
2. the intake list (path, size, hash, scan verdict, detected type, uploader, time);
3. the mapping tables used;
4. the per-memo audit `memo.imported` (row hash, legacy number, file hashes, batch);
5. the report and manifest (records, with hashes in `memo.import.completed`);
6. the verification pass.

All of them are in the tenant's hash chain (ADR-0019).

---

## R11.3 Mapping legacy data to our model

**What the code fixes for us** (read 2026-11-16):
- **Statuses:** `MemoStatus` is DRAFT, IN_REVIEW, RETURNED, APPROVED, REJECTED, WITHDRAWN, DISCARDED, CANCELLED, CLOSED (`MemoStore.kt`). The CLOSED check needs `closed_at` (`V9__lifecycle.sql`).
- **The workflow CHECK:** `memo` requires `workflow_instance_id` for every status except DRAFT and DISCARDED (`V1__memo.sql`). So imported final-state memos need the CHECK scoped to native memos (expand migration).
- **Initiator:** `initiator_id uuid NOT NULL`, which needs the same scoping.
- **References:** `reference` is 1–100 characters and unique per tenant. `number_series`, `number_period`, `number_scope` and `number_sequence` are all null or all set, and the register index covers set ones only (`V8__numbering.sql`). Imported numbers sit outside the register naturally.
- **Users:** `UserStatus` is INVITED, ACTIVE, LOCKED, DEACTIVATED. Leavers who had accounts exist as DEACTIVATED users and can be linked by identifier. People who never had an account cannot.
- **Readers:** `ParticipantKind` (INITIATOR, COAUTHOR, APPROVER, NOTIFIED, COPY, SHARED) plus `memo.read.unit` over `origin_unit_id` (ADR-0050).
- **Metadata:** required fields are enforced only with `requireAll` at submit (`MemoMetadata`).
- **File types:** `FileType.NEVER_ALLOWED` = DOCM, XLSM, PPTM, OLE2 (`FileType.kt`). The comment says OLE2 is refused because macros cannot be checked "without Apache POI".

**Statuses.**

| Legacy (examples) | Ours | Rule |
|---|---|---|
| Approved, Sanctioned, Accepted | APPROVED | Decided-on date required |
| Approved and filed, Closed, Archived | CLOSED | Closed-on date required (sets `closed_at`) |
| Rejected, Declined, Not approved | REJECTED | Decided-on date required |
| Withdrawn by initiator | WITHDRAWN | — |
| Cancelled, Void | CANCELLED | — |
| Draft, Pending, In process, Returned for clarification | — | **Refused** (`IN_FLIGHT_NOT_IMPORTED`): finish or re-raise in the new system |

- The mapping is the batch's table, not code (AGENTS §3.5). Defaults are proposed from the Lists sheet, and the dry run shows every distinct legacy value with its count.
- **Industry evidence on in-flight work:**
  - Microsoft: "Workflow definitions migrate, however in progress workflow information isn't migrated." It appears "as if the workflow was never started"; "we recommend you finish the workflow before migration." https://learn.microsoft.com/en-us/sharepointmigration/migration-assessment-scan-workflow-running-solutions-2010
  - ShareGate: no API preserves workflow tasks and history, so "ShareGate Migrate does not support the migration of workflow tasks and workflow history." https://help.sharegate.com/en/articles/10236599-workflow-status-history-and-tasks-are-not-preserved
  - We do keep the history, as legacy facts, because the spreadsheet carries it.

**People who have left, or are unknown.**

| Option | Verdict |
|---|---|
| Keep the name (and identifier) as text; link to a user only by an explicit identifier the admin confirms | **Chosen** |
| A placeholder account per unknown person (DEACTIVATED) | Rejected: invents identities; can be reactivated, granting access to history; clutters pickers and counts; has no email to match on later |
| The importing admin, or a "migration" account, as initiator | Rejected: falsifies authorship. Some tools fall back to the migrating account (Quest documentation in search results; the page refused automated fetch, unconfirmed) |
| Match by display name | Rejected: ambiguous ("R K Bansal", "Shri RK Bansal" are one person in the ICSSR SOP's own example) |

- Identifiers accepted: email, or the tenant's employee number if the directory holds one (the design checks the org module).
- A row whose initiator is not linked is still imported. It gets a warning, `initiator_id` is null, and the legacy name shows.

**Legacy approvers.**
- A historical list, never a workflow.
  - Our approvals are bound to a document hash and an authentication context (ADR-0036, ADR-0051), and the legacy system's were not.
  - Recording them as `workflow_action` rows or stamps would claim what we cannot verify.
- Linked approvers become `APPROVER` participants (read access, as they had in the legacy system), subject to clearance.

**Classification.**
- TNA: if sensitivity markings "cannot be mapped or migrated", sensitive information risks being "inappropriately handled or accessed" (edrms.pdf, Appendix, Metadata risks).
- Rules:
  - an explicit map to tenant levels;
  - never to a less strict level;
  - unknown is an error;
  - blank only with a batch default stated in the report;
  - a per-file level may only be stricter than the memo's (ADR-0046).

**Legacy numbers.**
- **Practice:**
  - Indian NIC eOffice gives a migrated physical file a **new** eFile number and puts "a sticker with eOffice file no. on corresponding physical file" (ICSSR eOffice SOP, v1.0, 12 January 2024, §D): https://icssr.org/sites/default/files/2024-05/SOP-v1.pdf
  - Our overview requires the legacy number to be kept, marked imported and outside the gapless series. We do not issue a second number; the legacy number is the memo's reference.
- **Continuing the series:** E01 research R2 (`epic-01-types-metadata-numbering/research.md`) found continuing a legacy series at go-live to be a normal request. ADR-0044's one-time starting number is the tool, and the dry run's collision check tells the admin which number to set.
- **Formats:** anything up to 100 characters after normalisation. Examples in the synthetic sample: `CR/2019/0042`, `HO-FIN-2021-117`, `0042/2018`.

**Units, types and dates.**
- **Units and file plan:** TNA notes that context lives in the file plan, and that if it cannot be recreated it should be held "in an additional metadata field". Legacy unit names are kept as text beside the mapped current unit.
- **Types:** these map to existing types, configured first. The representative types come in E07. An unmapped type is an error, not an automatic "Legacy" type, so classification defaults and retention come from a real type.
- **Dates:** see the conclusions. The report lists, per row, every date as given and as stored.

---

## R11.4 Dry run, idempotency, partial failure and the reconciliation report

**What products and tools do.**
- **Alfresco bulk import:**
  - The dry run "does everything short of writing", but "metadata validation only occurs upon write to the repository", so it is not checked against the data dictionary (maintainer, 8 April 2017): https://groups.google.com/g/alfresco-bulk-filesystem-import/c/j5tcfJhUmfE
  - Metadata that violates the model "will terminate the bulk import process" (Alfresco 5.1 docs, via search result): https://docs.alfresco.com/5.1/concepts/cs-file.html
  - Re-running with replace off resumes "from wherever it left off" (maintainer, 2017, via search result): https://github.com/pmonks/alfresco-bulk-import/wiki/Preparing-the-Source-Content
  - **Do better:** our dry run runs every validator, and one bad row never stops the batch.
- **SAP S/4HANA Migration Cockpit:**
  - An optional "simulate import" step comes before import (SAP Community blog): https://community.sap.com/t5/enterprise-resource-planning-blog-posts-by-members/step-by-step-guide-to-data-migration-cockpit-in-sap-s-4hana-public-cloud/ba-p/14111046
  - KBA 3782784, "Simulation of Migration Object Material Bom loads data into target system": https://userapps.support.sap.com/sap/support/knowledge/en/3782784
  - **Lesson:** make the dry run unable to write. That is the `READ ONLY` transaction, plus a test asserting that business tables are unchanged after a dry run.
- **Microsoft SPMT reports** (https://learn.microsoft.com/en-us/sharepointmigration/using-the-sharepoint-migration-tool-reports):
  - `SummaryReport.csv`: one row with totals (bytes, files, migrated, not migrated, warnings, duration, GB/hour, round).
  - `FailureSummary.csv`: only when failures occur.
  - Per item: Source, Destination, File name, Extension, File size, Status, **Result category**, Message, Error code, Source item ID, Destination item ID, Incremental round.
  - `ScanSummary.csv`: a scan before migration, with "items with issues" and "items filtered out".
  - The incremental round number "indicates if the scan or job was rerun".
  - Tasks can be paused and resumed.
- **AWS DMS validation:**
  - "compares each row in the source with its corresponding row at the target … and reports any mismatches".
  - Failure types: `RECORD_DIFF`, `MISSING_SOURCE`, `MISSING_TARGET`.
  - It stops after 10,000 failures.
  - https://docs.aws.amazon.com/dms/latest/userguide/CHAP_Validating.html
- **M-Files Importer:** imports files from the file system with metadata from Excel, XML or CSV; hot-folder automatic imports; "Detection of duplicates during import". https://catalog.m-files.com/?p=64754
- **OpenText Content Server Object Importer:**
  - Driven by an XML control file that "can create, update, or delete objects", with permissions and attributes (third-party blog): https://www.oshyn.com/blog/importing-documents-into-opentext-content-server
  - It writes logs and "unprocessed"/"uncreated" output files (forum, unconfirmed): https://forums.opentext.com/forums/developer/discussion/245509/object-importer-problem-with-multiple-attribute-sets
- **NIC eOffice File Migration:**
  - Manual, one file at a time: scanned files are placed on a document server with folder permissions; the user searches by physical file number, imports, enters metadata, then "Generate" and "Finalize migration".
  - There is no bulk import or reconciliation report.
  - https://www.gconnect.in/eoffice-how-to-use-file-migration (screenshots around August 2021)
  - The ICSSR SOP requires legacy files in use to be digitised, "Create an efile with reference to corresponding physical file", and every movement to be recorded (link above).
  - Bangladesh e-Nothi: no primary source found on legacy import (unconfirmed, as in E05's research).

**Problem categories** (codes in the design):

| Level | Effect | Examples |
|---|---|---|
| **Batch** | Nothing proceeds | Not XLSX or CSV; not UTF-8; header missing or unknown columns; duplicate column names; over the row or size caps; parser timeout or malformed container |
| **Row error** | Row rejected, others continue | Required value missing; unparseable or impossible date (decided before raised); unknown status, type, classification or unit; in-flight status; legacy number too long, duplicated in the batch, already used, or producible by a live series; subject longer than 300; no MAIN file; a file reference missing or ambiguous; a referenced file rejected (malware, type, size, encrypted); SHA-256 given and different; same key with a different fingerprint |
| **Row warning** | Imported, listed | Initiator or approver not linked (kept as text); case-insensitive path match; formula cached value used; blank classification given the batch default; date without time |
| **File** | Listed | Received but referenced by no row; the same content (hash) referenced by two rows; zero-length (error for its row) |
| **Already done** | Skipped | Same key and fingerprint as an imported memo |

**Idempotency, resume and partial failure.**
- **Keys:** the IETF draft (`draft-ietf-httpapi-idempotency-key-header-08`, 22 October 2026, active) pairs a key with an optional "idempotency fingerprint … to determine the uniqueness of a request". https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
  - Our row key and fingerprint follow it.
  - Uploads already carry idempotency keys (`UploadRequest.idempotencyKey`). The intake key is derived from (batch, path, size).
- **One row is one transaction.**
  - Promotion uploads happen before it, through the pipeline's pending-upload pattern (ADR-0029 steps 5–6). An orphaned object after a crash is swept as today.
  - The memo rows, legacy tables, file bindings and the audit entry commit together.
- **Resume:**
  - the job reads PENDING rows in chunks, keyset-ordered by row number;
  - each run is a tenant one-off (`Jobs.tenantTask`, `TenantScopedPayload`) that reschedules itself;
  - a node failure leaves the chunk to db-scheduler's dead-execution handling;
  - the row state guards against double work (a guarded update, AGENTS §4).
- **Stopping:** a "stop" on the batch is honoured between chunks.
- **No undo:** imported memos are records (AGENTS §3.3). The safety is the dry run, a rehearsal on a non-production tenant (TNA), and corrections as appended entries.

**The reconciliation report.**
- **Header:**
  - tenant, batch id, source code;
  - started and finished by whom, and when (UTC);
  - the step-up time;
  - the workbook's name, size and SHA-256;
  - the time zone used for dates;
  - the mapping tables (each with its hash);
  - the software version.
- **Totals** (SPMT-like):
  - rows read, imported, rejected (by code), skipped as already imported, conflicting;
  - files received, referenced, imported, unreferenced, rejected (by reason);
  - bytes;
  - PDF renditions done, failed or not possible; text likewise.
  - The **balance lines** must hold, or the summary says UNBALANCED.
- **Rows:** row number, legacy number, result, codes and messages, memo id, the dates as given and as stored.
- **Files:**
  - relative path and size;
  - SHA-256 at intake, expected (if given) and stored;
  - verification result;
  - file version id;
  - rendition states.
- **Manifest:** `manifest-sha256.txt` of every stored original, as RFC 8493 §2.1.3.
- **Kept as records** with the batch, with hashes in `memo.import.completed`. Downloadable by holders of `memo.import.manage` and auditors.
  - Subjects are as confidential as the memos. The report is classified at the highest level in the batch, so only people cleared for that level read it.
- **Renditions** finish after the import. The report is final once no rendition of the batch is PENDING. Until then it shows "renditions in progress" and is regenerated.

---

## R11.5 What to adopt, and what to do better

**Adopt:**
- a scan or dry run before migrating, and a summary first with the failures only when there are any (SPMT);
- per-item result categories and codes (SPMT);
- duplicate detection (M-Files);
- re-runs that resume and skip what is done (Alfresco, SPMT rounds);
- BagIt's complete versus valid, and a SHA-256 manifest;
- NDSA's "verify if given, generate if not";
- TNA's rehearsals, attention to dates, and keeping provenance;
- Microsoft's "finish in-flight workflows first".

**Do better than the products studied:**
- **A dry run that validates metadata and content**, not only the file system (Alfresco), and cannot write (the SAP KBA).
- **One bad row never stops the batch** (Alfresco terminates).
- **Unknown people stay as text.** We never substitute the migrating account (the Quest pattern, unconfirmed) or invent placeholder users.
- **Legacy approvals are shown honestly** as unverified history, not dropped (ShareGate, SPMT) and not dressed up as authenticated approvals.
- **A report that must balance**, with hashes verified at three points and kept as a record in the audit chain, not a log file beside the tool.
- **Bulk, not one file at a time** (NIC eOffice), with the legacy number kept as the reference instead of a second number on a sticker.

---

## Fit with the existing system: what E06 reuses

| Need | Existing piece |
|---|---|
| Upload of the workbook and every original; scan, detection, encryption, SHA-256, audit | `files` upload pipeline (ADR-0029); `FileUploads` and `ProgrammaticUploads` for promotion from a stream |
| ZIP and OOXML container checks | `ZipInspector` (entries, ratio, size, `[Content_Types].xml` with DTDs refused) |
| PDF and text of originals | `files_rendition` jobs (ADR-0045), ONLYOFFICE conversion, the Tika server; `FilePurpose.renditions` |
| OLE2 subtype detection | Tika server `/detect/stream` (new call on the existing `TikaClient`) |
| Record storage, locks and fixity | Record purposes and retention extension (ADR-0029); the weekly fixity job (ADR-0053), extended to imported originals |
| Background work with tenant context | `Jobs.tenantTask`, `TenantScopedPayload`, `TenantTransactions` |
| Audit per memo and per batch | `AuditLog.record` in the business transaction (ADR-0019) |
| Legacy numbers and collision check | `memo.reference` unique index; `NumberFormats` (render), `MemoNumberingChecks` (series overlap) and the starting number (ADR-0044, `MemoNumberingAdmin`) |
| Readers of imported memos | `memo_participant` kinds and `memo.read.unit` (ADR-0050); clearance |
| Partial metadata on imported memos | `MemoMetadata` validation without `requireAll` |
| Search over imported memos and their text | E05 search tables and indexer (ADR-0054), with an "Imported" filter |
| The report's PDF summary | `PageRenderer` (ADR-0052) |
| The report's workbook | POI SXSSF (already linked) |
| Step-up for starting an import | The Phase 2 step-up |
| Synthetic legacy sample (tests, demo, E07) | `demo` generators (`DemoDocx`, `DemoPdf`, `SyntheticVolumes`) extended to write a sample folder and workbook |
| Nightly fuzzing | The Jazzer set-up (TESTING_STRATEGY §3.6), with a new target for the CSV and XLSX readers |

**Schema consequences for the design** (all expand-only):
- `memo.origin` (`NATIVE` | `IMPORTED`, default `NATIVE`);
- the workflow and initiator CHECKs scoped to native memos;
- legacy date columns or a side table;
- a unique (tenant, source, legacy number) index for imported memos;
- legacy party and legacy approval tables;
- batch, intake, row and problem tables, with RLS forced (AGENTS §3.2).
- Every list, inbox, dashboard and notification query that assumes a non-null initiator or a workflow instance is checked in the design.
