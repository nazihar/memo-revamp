# E04 Final rendition (PDF/A) — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. CI stages: PR (unit, property, integration, matrix, smoke E2E), nightly (hostile PDFs, browsers, visual, pseudo-locale), weekly (load).

## Scope and risks
- **Risks covered:**
  - a record that is not PDF/A, or that differs from what was approved;
  - hostile content carried into the record;
  - classification leaking through the record;
  - a record changed after storing;
  - regeneration used to replace a record.
- **Seed data:**
  - the demo tenant's approved memos;
  - a memo with a Word document, a PDF attachment, an .xlsx, an image and a ZIP;
  - an attachment classified above the memo;
  - `auditor@` (`audit.read`);
  - the admin with `memo.rendition.regenerate`;
  - a reader not cleared to the record's level.
- **Fixtures:**
  - hostile PDFs (JavaScript, `OpenAction`, launch action, forms, non-embedded fonts, CMYK, transparency, encrypted, broken xref);
  - a 2,000-page PDF;
  - generated in tests with PDFBox where possible. No binary fixture without its generator, except the two ONLYOFFICE outputs recorded in T1.

## Tests by task
| Task | Layer | Test |
|---|---|---|
| T1 | Spike (recorded) | ONLYOFFICE `pdfa` on four document kinds: part and level, veraPDF verdict, time; veraPDF time on 10, 60 and 200 pages; heap use |
| T1 | Integration | `PdfaConversionIT` (stack, nightly like E02's conversion IT): `toPdfA` on a .docx and an .xlsx gives files veraPDF accepts, or the expected rasterising fallback |
| T1 | Unit | `PdfaValidator`: a known-good and a known-bad file; the time limit; a parser exception becomes "not validated", not a crash |
| T2 | Golden | Render-and-compare: cover, approval history (30 stamps), note sheet (long notes, lists, links, corrections), placeholder; English names and remarks; PNG at 100 dpi with a tolerance |
| T2 | Property | Random Unicode strings: no exception; the U+FFFD count is correct; extracted text equals the input after normalising, for characters the font has |
| T2 | Validation | Generated-only document passes veraPDF 2u |
| T3 | Unit and integration | Assembler: each hostile fixture gives a valid 2u record with the feature removed or the part rasterised and flagged; the outline has one entry per part; page labels per part; exactly one output intent; XMP has `pdfaid` 2/U and the `memo:` fields; the text check finds the key items in order |
| T3 | Resilience | 2,000-page part becomes a placeholder; a part over time is retried, then becomes a placeholder; heap stays under the budget (`MemoryUsageSetting` temp-file mode) |
| T4 | Integration | `FinalRenditionIT`: approval → job → READY; the cover's number and document hash; the part rows; the record classification from a restricted attachment; `HASH_MISMATCH` stores nothing; one PENDING or BUILDING per memo; the backfill creates version 1 for an old approved memo, once |
| T4 | State machine | Every allowed transition (PENDING→BUILDING→READY, BUILDING→PENDING on retry, BUILDING→FAILED, FAILED→PENDING on retry) and every forbidden one (READY→anything, FAILED→READY, a second open version) |
| T4 | Persistence/RLS | `RlsMetaTest` covers both tables; append-only trigger on parts; cross-tenant read refused |
| T4 | Matrix | `p3-e04-renditions.yaml`: `GET rendition`, download, regenerate, retry × initiator, approver, copy, shared, unit reader, auditor, regenerate holder, under-cleared, other tenant |
| T4 | Contract | OpenAPI drift for `RenditionView` and `MemoView.rendition` |
| T5 | Integration | Regenerate: permission, step-up, reason; version 2 READY and version 1 still readable; `superseded`. Retry of FAILED. Closure extends the lock (object-lock test reads the retention). The fixity job: a clean run audits `verified`; a changed object (test hook as the storage owner) audits `mismatch` |
| T5 | Audit coverage | Every `memo.rendition.*` action asserted (`AuditActionCoverageTest`) |
| T6 | Unit (web) | The Final record box in each state; polling while pending; under-cleared message; auditor details; regenerate and retry dialogs; axe in English and Arabic |
| T6 | Visual (nightly) | Final record box (ready, pending, failed, auditor view): light and dark, LTR and RTL, phone |
| T7 | E2E (smoke) | `final-record.spec`: approve a memo with a Word document and a PDF attachment → the box turns ready → download → the downloaded file passes veraPDF 2u (run in the test with the library) and its text contains the subject; regenerate with a reason → version 2 listed, version 1 still downloadable |
| T7 | Performance | §9 budgets in `RenditionPerformanceIT` (generated pages, assembling a 60-page record without the converter, veraPDF on 200 pages); the end-to-end job time on dev recorded in the log |

## Special tests (`TESTING_STRATEGY §12`)
- **Hostile PDFs (nightly):** the T3 fixtures plus deeply nested objects, huge images and broken streams, through the assembler with bounded time and memory.
- **Record integrity:** object lock on `memo.rendition` (delete and shorten refused), the part table append-only, the fixity job.
- **i18n:** the record is English only. The box's strings exist in English and Arabic; pseudo-locales run nightly.

## Exit checklist
- [ ] Every test above green in its stage
- [ ] Matrix and audit coverage green with the new endpoints and actions
- [ ] Spike recorded in the log; ADR-0053 updated if the converter decision changed
- [ ] ASVS rows (V5, V8, V12, V14, V15, V16)
- [ ] Dependency register: veraPDF (MPL-2.0 option) and its transitive Saxon-HE, Rhino, Jackson 2; Noto Sans (OFL-1.1)
- [ ] Runbook for final records
