# E04 Final rendition (PDF/A) — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows, with mitigations mapped to OWASP ASVS 5.0 requirement areas. Builds on the E02 threat model (files, conversion) and the E03 one (notes, access, approvals).

## Data flows
1. **Build:** the approval → the job (system actor) → the gathering read → ONLYOFFICE `pdfa` (the document, Office attachments) → PDFBox (normalise, merge, generated pages) → veraPDF → the files pipeline (scan, encrypt, records bucket, lock) → `memo_rendition` → audit.
2. **Read:** reader → `GET /memos/{id}/rendition` → download through the files API (purpose `mayRead`, clearance) → `file.opened`.
3. **Change:** regenerate (permission, step-up, reason) → new version; closure → retention extension.
4. **Fixity:** weekly job → decrypt and re-hash → audit.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Someone triggers a rebuild to replace an inconvenient record | 3 | Regeneration needs `memo.rendition.regenerate` (granted on purpose) and a fresh step-up, with a reason. Version 1 stays locked and readable; `superseded` is audited with both hashes | V8.2, V6.3 (step-up), V16.2 |
| S2 | A forged job payload builds a record for another tenant | 1 | Tenant and actor bound from the task data by the jobs framework (ADR-0018); RLS on every table; the memo is read in the tenant's transaction | V8.4, V15.3 |
| T1 | The record is changed in storage | 1, 4 | Object lock in the tenant's mode (records bucket, ADR-0005); the SHA-256 in the file version and in the hash-chained audit log; the weekly re-hash audits mismatches; storage endpoints internal only | V14.2, V16.4 |
| T2 | The record differs from what was approved | 1 | The document part is the version bound by the final stamps, and its SHA-256 must equal the stamps' document hash, else the build fails (`HASH_MISMATCH`). The document is never re-filled (ADR-0053) | V2.3 (business logic), V16.4 |
| T3 | A hostile attachment PDF carries JavaScript, launch actions or forms into the record | 1 | Normalisation removes actions, JavaScript, `AA`, forms and banned annotations; veraPDF checks 6.5.x; parts that still fail are rasterised | V12.3 (files), V5.2 |
| T4 | Markup or layout injection through user text (subject, notes, names) | 1 | Generated pages take plain strings in a typed model; no HTML; control characters removed; note JSON rendered by a closed walker (ADR-0049, ADR-0052) | V5.3 |
| R1 | Nobody can prove when the record was made or by what | 1, 3 | `memo.rendition.created` with the hash, the validation and the renderer versions; renderer versions in the cover and the XMP; the part table names each source version and hash | V16.2 |
| I1 | A restricted attachment leaks through the record | 1, 2 | The record's classification is the highest of the memo and its included attachments; download needs clearance to it; readers below see only that it exists | V8.2 (attribute-based), V14.1 |
| I2 | The record is downloaded by someone who may no longer read the memo | 2 | `mayRead` applies the memo read rule (ADR-0050) at download time, not at build time; reads audited | V8.3 |
| I3 | Logs or audit events carry content | 1, 4 | Events carry ids, hashes, counts and rule ids, never text; the spool is deleted after the job, and spool files are created owner-only | V16.3, V14.2 |
| D1 | A huge or crafted attachment exhausts the job (pages, memory, time) | 1 | Page caps per part and per record; PDFBox temp-file memory mode; converter timeouts; veraPDF on a bounded executor with a time limit; the job runs on the jobs pool, never in a request | V12.1, V15.2 |
| D2 | A memo approved again and again (or the backfill) floods the converter | 1 | One PENDING or BUILDING version per memo (unique index); the backfill does 100 per tenant per run; conversions are limited to 2 per job and by the converter's own pool | V15.2 |
| D3 | veraPDF parser vulnerabilities (XXE, resource exhaustion) | 1 | 1.30.3 with its security fixes; Renovate tracks it; bounded executor and time limit; it parses only files we assembled | V15.1 (components) |
| E1 | The rendition job's system actor is used to read or write other files | 1 | The purpose's `mayUpload` allows only that actor for `memo.rendition`; the job reads sources through `FileContents` for the memo's own versions only | V8.2, V15.3 |

## Residual risks
- **Storage administrators** with filer or volume access can still delete chunks under the S3 lock. The weekly re-hash detects it; prevention depends on the deployment (network policy, ADR-0005). The E07 security gate checks this.
- **A wrong-but-valid record:** ONLYOFFICE renders a document slightly differently from Word. The record shows our conversion of the approved file, whose original stays locked with its hash, so the original can always be produced.
- **Rasterised pages** have no text, so they are not searchable or accessible. They are flagged and counted. If they turn out common, a customer may justify a commercial converter.
- **No cryptographic seal yet.** Integrity rests on the lock, the hash and the audit chain until PAdES B-LTA (the upgrade path).
