# E04 Final rendition (PDF/A) — log

## 2026-11-15 — Plan written
- Research R1, R3, R8 and records practice by two research agents, checked by the lead (`research.md`).
- ADR-0052 (generated pages: one interface, PDFBox with Noto Sans for English) and ADR-0053 (one PDF/A-2u record per approved memo, validated by veraPDF, locked and re-hashed), both accepted under the delegated gate.
- Design, threat model, tasks T1–T7 and test plan written. No screen-flow review is needed for E04 (overview §10 Q1); the decisions taken under the delegation are listed in `ux-review.md` for the owner.
- **Changes against earlier plans:**
  - ADR-0048's "second fill" at approval is dropped. Re-filling would change the document the stamps bind (ADR-0051).
  - The overview's PDF/A-3 option for attachments that cannot be converted is replaced by placeholder pages naming the locked originals by hash.

## 2026-11-15 — T1 spike: ONLYOFFICE `pdfa` and veraPDF
- **Setup:** ONLYOFFICE Docs 10.0.0.1 in the local Compose stack (`full` profile), called through `OnlyOfficeConverter.toPdfA` (`outputtype: "pdfa"`). veraPDF 1.30.3 (Greenfield, `validation-model-jakarta`). Inputs built with POI in `PdfaConversionIT`: a Word memo (40 paragraphs, a table and a semi-transparent PNG), a spreadsheet and a three-slide presentation.
- **Results:**

  | Input | Conversion | Size | Part and level claimed | Valid as claimed | Valid as PDF/A-2u |
  |---|---|---|---|---|---|
  | Word memo | 2.1 s | 75 KB | 2a | No: 6.7.3.3-1 only (level A's tagged structure) | **Yes**, no failed rule |
  | Spreadsheet | 2.1 s | 22 KB | 2a | No: 6.7.3.3-1 only | **Yes** |
  | Presentation | 2.1 s | 21 KB | 2a | No: 6.7.3.3-1 only | **Yes** |

  veraPDF took 1.3 s on its first file (profile loading) and under 0.1 s afterwards.
- **Conclusions:**
  - ONLYOFFICE's `pdfa` output is PDF/A-2 with embedded fonts and Unicode mappings, and passes 2u. Office documents and attachments need no rasterising. The record re-declares itself as 2u, which is what it meets.
  - The forum report that `pdfa` output fails accessibility checks matches the single 6.7.3.3-1 failure (tagging), which only level A requires.
  - Rasterising stays as the fallback for uploaded PDFs that break a rule (non-embedded fonts, CMYK).
- **Changes against the design:**
  - PDFBox's jar has no sRGB profile; the output intent uses the JDK's own (`ICC_Profile.getInstance(CS_sRGB)`).
  - The record's metadata uses only predefined XMP properties. The number is `dc:identifier`; the hashes and versions are in `dc:description`. A custom `memo:` schema would need a PDF/A extension schema for no gain.
  - Noto Sans ships with Italic and Bold Italic too (notes can be italic), about 1.7 MB in all.

## 2026-11-15 — T2 and T3 built
- **T2, generated pages (#115):** `PageRenderer` and its page model; the PDFBox renderer with the four Noto Sans faces; golden images reviewed by eye; a jqwik property; veraPDF accepts generated pages with our metadata as 2u.
- **T3, the assembler (#116):**
  - parts are normalised and each uploaded PDF is checked by veraPDF on its own; the validator decides rasterising, not guesswork;
  - encrypted or broken files become placeholders; images get one page each;
  - one output intent, our metadata, bookmarks and page labels;
  - a hostile PDF (JavaScript, forms, Helvetica not embedded, CMYK) ends up in a valid 2u record.

## 2026-11-15 — T4 the final rendition job and API
- **Migration `memo/V17`:**
  - `memo_rendition` (versions, state, the stored file, hash, classification, validation, flags, renderer, attempts);
  - `memo_rendition_part` (append-only);
  - at most one open version per memo; column-level update grants.
- **Planning (`RecordPlanner`):**
  - reads everything as the system for the tenant, with no reader rule;
  - the document is the one the final stamp binds, and a hash mismatch fails for good (`HASH_MISMATCH`);
  - the attachments are those bound to the final round;
  - the record's classification is the highest of the memo and its attachments;
  - memo references to memos classified above this one print as "restricted".
- **Pages (`RecordPages`):** a cover; approvals by round with authentication, document hash and the chain; the note sheet from `noteDoc/v1`; placeholders. English, times in UTC.
- **Parts (`RecordSources`):** PDFs and pictures go in as they are; Office files are converted with `toPdfA`; anything else becomes a placeholder. A converter outage is retried.
- **Building (`RecordBuilder`):**
  - claim, plan, assemble, validate, text check;
  - stored through the files pipeline as purpose `memo.rendition` (record storage, scanned, encrypted, locked);
  - READY with its parts; `memo.rendition.created` audited.
  - Up to 5 attempts; a task that starts before the approval commits finds no row and retries.
- **Files module:**
  - `FilePurpose.retentionYears(owner)`: the memo type's years when they count from approval;
  - `FileUploads.uploadFile` and `uploadVersionFile`, streamed from a file.
- **Jobs and API:**
  - the approval's transaction asks for version 1;
  - a backfill every 6 hours gives memos approved before E04 their version 1, 100 per tenant per run;
  - `GET /memos/{id}/rendition`: auditors get the hash and validation; download only when cleared to the record's level;
  - `MemoView.rendition` (NONE, PENDING, READY, FAILED).
- **Tests:** `FinalRenditionIT`:
  - approval → PENDING → build → READY;
  - six parts in the expected modes;
  - the downloaded file passes veraPDF;
  - the audit events;
  - the hash-mismatch failure stores nothing;
  - the backfill runs once.

  The test converter writes a PDF/A for `toPdfA`.
- **Changes against the design:**
  - The cover cannot say how many pages were rasterised, because it is drawn before the assembly. The flags are in the record row and the auditor's view.
  - Retention counts from when the record is stored, minutes after approval. For backfilled memos that is later than approval, which errs on the longer side.

## 2026-11-15 — T5 regeneration, retry, retention and fixity
- **Permission:** `memo.rendition.regenerate`, privileged and granted on purpose, so no built-in role holds it. Its label is in English and Arabic.
- **Regenerate:**
  - `POST /memos/{id}/rendition/regenerate` needs the permission, a fresh step-up, a reason of 1–500 characters, and an approved or closed memo with no version being made;
  - it makes version 2 as a new version of the same record file;
  - version 1 stays READY and openable, and `memo.rendition.superseded` names both hashes and the reason.
- **Retry:** `POST /memos/{id}/rendition/retry` takes the failed newest version back to PENDING with its attempts reset.
- **Retention at closure:**
  - closing (by the initiator or automatically) schedules `memo-rendition-retention`;
  - when the type counts retention from closure, every made version is kept until closure plus its years;
  - files gains `RecordRetentions.extend`, which extends storage first and then the row; files/V3 lets the append-only guard accept a later `retain_until`, and nothing else;
  - audited as `file.retention.extended` and `memo.rendition.retention_extended`.
- **Fixity:**
  - `memo-rendition-fixity` runs weekly per tenant;
  - it reads every READY version through the files service in pages of 100, decrypts, re-hashes and compares with the recorded hash;
  - `memo.rendition.mismatch` for each bad one, and `memo.rendition.verified` per run.
  - The sampling switch above 10,000 records (design §6) is carried; every record is checked for now.
- **Tests:** `RecordLifecycleIT`:
  - regenerate is refused without the permission (403), without access to the memo (404) and without a reason (422), then version 2 is made and version 1 still opens;
  - a failed version is retried;
  - closure under a 25-year type extends the lock past the 10-year default, and doing it again extends nothing;
  - the fixity check passes, then finds a changed hash.
  - Matrix entries for the two endpoints.
