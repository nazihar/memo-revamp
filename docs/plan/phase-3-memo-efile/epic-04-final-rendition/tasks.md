# E04 Final rendition (PDF/A) — tasks

> Step 5 of `AGENTS.md §5`. Vertical slices; each task is its own PR, stacked until the owner merges. No screen-flow review is needed for E04 (overview §10 Q1); `ux-review.md` is for information.

| Task | Goal | Touches | Size | Depends |
|---|---|---|---|---|
| T1 | **Spike, then PDF/A conversion and the validator:** ONLYOFFICE `outputtype: "pdfa"` on a filled template, a Word memo with tables and images, a spreadsheet and a presentation; veraPDF 1.30.3 on each (which part and level, and does it pass); timings. Then: `RenditionConverter.toPdfA`; a `PdfaValidator` port in the memo module with the veraPDF implementation (bounded executor, time limit); the dependency register; image size | docedit, files (port), memo, build | M | — |
| T2 | **Generated pages:** the `PageRenderer` port and `PageSpec` model; the PDFBox renderer with Noto Sans TTF (OFL, licence file); wrapping, tables, page breaks, header and footer; the U+FFFD count; golden render-and-compare tests; a veraPDF 2u test of generated-only pages | memo (rendering package), resources | M | — |
| T3 | **The PDF/A assembler:** normaliser (actions, JavaScript, forms, annotations, transparency groups); merge; one sRGB output intent; XMP (`pdfaid`, Dublin Core, predefined properties only); outline and page labels; the rasterising fallback; images to pages; placeholders; the text check; hostile and edge inputs (JavaScript, missing fonts, CMYK, encrypted, ZIP, 2,000 pages) | memo | L | T1, T2 |
| T4 | **The final rendition job and API:** `memo/V17`; content gathering (cover, stamps with the chain, note sheet from `noteDoc/v1`, attachments); the hash check; classification; purpose `memo.rendition` and `RecordRetention` in files; storing with retention; audit; `GET /memos/{id}/rendition`; `MemoView.rendition`; the backfill; the matrix | memo, files, workflow (listener only), authz (permission) | L | T3 |
| T5 | **Lifecycle:** regenerate (permission `memo.rendition.regenerate`, step-up, reason) and retry; the retention extension at closure; the weekly fixity job with sampling; metrics; alerts | memo, files, authz, identity (step-up) | M | T4 |
| T6 | **Web: the Final record box:** states and polling, download, the auditors' details (hash with copy, validation, flags, versions), history, the retry and regenerate dialogs with step-up; i18n (English and Arabic); unit tests; visual baselines | web | M | T4, T5 |
| T7 | **Close:** E2E journey (approve → record ready → download, validated as PDF/A-2u in the test; regenerate with a reason); the demo (backfill on dev, the permission for the admin); performance of §9; ASVS rows; runbook (final records: building, failures, validation, fixity, regeneration); STATUS and log | all | M | all |

**Order:** T1 and T2 in parallel; T3 after both; T4, T5 and T6 in order; T7 last.

## Acceptance criteria (selection; the rest in `test-plan.md`)
- **T1:**
  - Given a filled template, a Word memo with a table and an image, an .xlsx and a .pptx, when converted with `pdfa`, then the log records the part and level ONLYOFFICE produced and veraPDF's verdict.
  - Given ONLYOFFICE output that is not valid PDF/A-2, then T3 rasterises it, and the spike says how often.
- **T2:**
  - Given a page spec with a 3,000-character note, a 64-character hash and 30 stamps, when rendered, then pages break without overflow, the golden images match, the extracted text contains every string in order, and veraPDF accepts it as 2u.
  - Given text with a character outside Noto Sans, then it is drawn as U+FFFD and counted.
- **T3:**
  - Given an attachment PDF with JavaScript, an `OpenAction` and a form, when assembled, then the record is valid PDF/A-2u and has no actions or forms.
  - Given a part with non-embedded fonts or CMYK, then it is rasterised and flagged, and the record is valid.
  - Given a ZIP, then a placeholder page names it with its SHA-256.
- **T4:**
  - Given a memo approved by its last approver, then within the job a READY record exists. It is valid as 2u, has bookmarks for each part, and its cover shows the number and the document's SHA-256. `memo.rendition.created` carries the record's SHA-256.
  - Given the stamps' document hash differs from the stored document's, then the build fails with `HASH_MISMATCH` and nothing is stored.
  - Given an attachment classified above the memo, then the record's classification is that level, and a reader below it gets 403 on download.
- **T5:**
  - Given regenerate with a reason and a step-up, then version 2 is READY, version 1 is still downloadable, and `superseded` names both hashes.
  - Given a stored record changed under the lock (test hook as storage owner), then the fixity job audits `mismatch`.
  - Given closure of a memo whose type retains from closure, then the lock is extended to closure + years.
- **T6:**
  - Given a reader not cleared to the record, then the box says so and offers no download.
  - Given an auditor, then the SHA-256 and the validation result are shown.
  - The pages pass axe.
