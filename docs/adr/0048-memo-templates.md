---
status: accepted
date: 2026-11-15
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0048 — Memo templates: tenant .docx with typed tags, sanitised on upload, filled on the server

## Context and problem statement
Banks write memos on their own letterheads, with fixed blocks: number, date, addressee, the memo type's fields, and an approval block. Phase 3 E01 left a template slot on the memo-type version (ADR-0043); E03 fills it. Tenant administrators upload a template they made in Microsoft Word, and the server fills it twice: when a memo is created from it (the initiator's starting document) and, from E04, for the final archival rendition.

The template author is partly trusted. A .docx can carry macros, ActiveX, OLE objects, external links and field codes that a later converter may follow, and some templating libraries evaluate expressions that can run code. How do admins write placeholders, which engine fills them, and how is a template made safe?

## Decision drivers
- Placeholders an admin can type in Word without the Developer tab, surviving Word's habit of splitting text across runs.
- Fidelity with real letterheads: headers, footers, tables with repeating rows, images, styles.
- No code execution from template content; no external fetches.
- A permissive licence (AGENTS §3); an engine we can maintain if upstream stalls.
- Validation at upload, so a broken template never reaches an initiator.

## Considered options
1. **poi-tl** (Apache-2.0, on Apache POI) with its default tag grammar.
2. **docx4j** (Apache-2.0) with OpenDoPE content controls.
3. **Our own small engine** on POI XWPF.
4. **ONLYOFFICE Document Builder** through the existing service.
5. docx-stamper / office-stamper / XDocReport (expression languages).

## Decision outcome
**Option 1, behind our own interface, with option 3 as the fallback that keeps the same syntax.**

- **Tags published to admins** (copied from a list in the admin screen, never typed from memory):
  - `{{memo.number}}`, `{{memo.date}}`, `{{memo.subject}}`, `{{memo.classification}}`, `{{memo.type}}`;
  - `{{initiator.name}}`, `{{initiator.position}}`, `{{initiator.unit}}`;
  - `{{meta.<fieldKey>}}` for the type's fields, pre-formatted (amounts with currency, dates in the tenant's format);
  - `{{?meta.<fieldKey>}}…{{/meta.<fieldKey>}}` for a block shown only when the field has a value;
  - the approval table: `{{approvals}}` in a cell of the header row (for example "Approver{{approvals}}"), and the row below it with `[name]`, `[position]`, `[decision]`, `[decidedAt]`. Only these fields may be in that row; a `{{tag}}` there is a problem.
  - ASCII keys only.
- **Engine use:**
  - None of poi-tl's expression engines: our own lookup answers each tag from a flat map of text, with no reflection (so not even the default dot-path engine runs). `useSpringEL()` and the SpEL classes are banned by an ArchUnit rule, and only the templating module may use poi-tl.
  - Picture, table, list and include tags (`@`, `#`, `*`, `+`) are refused at fill as well as at upload.
  - Data is a map of pre-formatted strings, and a list of maps for the approval table.
  - A missing or invalid tag fails the fill; nothing is printed blank silently.
  - Fills run on a bounded executor with a timeout.
- **Sanitising on upload:**
  - The size is limited (10 MiB), and POI's `ZipSecureFile` limits are lowered at startup.
  - Only the `.docx` main content type is accepted for now. A `.dotx` is refused by the upload pipeline's type check; admins save it as `.docx`. Accepting `.dotx` (rewriting its content type) is carried.
  - The upload is **refused** (not cleaned silently) if it contains macros (`vbaProject.bin`), ActiveX, OLE objects, `altChunk`, `attachedTemplate`, external relationships other than hyperlinks, or `INCLUDE*`/`LINK`/`DDE` field codes.
  - The checks run as the purpose's content inspection inside the files pipeline (scan, inspection, encryption; ADR-0029), on its bounded pool and time limit. A package that passes every rule is stored as uploaded. Everything filled from it is written by POI, so what reaches ONLYOFFICE and initiators is always re-serialised.
  - Relationship types are an allow-list: a type that is neither known-safe nor known-dangerous is refused as unsupported.
- **Validating on upload:**
  - a tag inventory over the body, headers, footers and footnotes, and the loop row;
  - every tag must be on the memo type's allow-list (the standard tags plus its field keys);
  - unbalanced or malformed `{{…}}` text is reported with its part, paragraph and excerpt;
  - a test fill with sample values, converted to PDF through ONLYOFFICE (ADR-0045), shown to the admin before the type version is submitted for review.
- **Where it lives:**
  - The template is a file of purpose `memo.template` owned by the memo-type version. Changing it is a type-version change under maker-checker (ADR-0043).
  - A memo records the template version it was filled from, so the final fill (E04) uses the same template and the snapshot of the memo's data.
- **Second fill dropped (2026-11-15, ADR-0053):** the final record does not fill the template again; it would change the document the stamps bind (ADR-0051). The number and the approvals appear on the record's generated pages.
- **First fill:** at create, the filled template becomes the memo's document version 1, by the initiator. A type without a template behaves as today (upload or blank).
- **The spike chose poi-tl 1.12.2 on POI 6.0.0** (E03 T1, 2026-11-15; `epic-03-templates-notes-access/log.md`). On both POI 5.5.1 and 6.0.0 it handled tags split across runs, headers, footers and the loop row. A letterhead fill took 11–13 ms at p50 and under 20 ms at p95 on the dev machine. It also works with Batik excluded. POI 6.0.0 was chosen for its extra zip hardening. Option 3 stays the fallback if poi-tl stalls on a later POI.

### Consequences
- Good: admins write templates in Word with tags they copy from the screen, and errors appear at upload with locations.
- Good: no expression evaluation, no external fetches, no macros reach the converter or an initiator.
- Bad: poi-tl has one maintainer and no release since 2024. Pinned, wrapped behind `TemplateFiller`, with a same-syntax fallback.
- Bad: POI adds about 15 MB to the image. Batik is excluded.
- Neutral: Word's own mail-merge fields and content controls are not supported; the tag list says so.

### Confirmation
- Unit tests: tag inventory and malformed tags (split runs included); every refusal rule, with a crafted .docx per rule; data escaping (a value with `{{`, `<`, `&` comes out as text).
- ArchUnit: no call to `useSpringEL`, no use of poi-tl's SpEL classes, no `org.springframework.expression` in the templating and memo modules, and poi-tl only in the templating module.
- Integration test: upload → validate → test fill → maker-checker → create from template gives document version 1 with the values.
- Performance: fill p95 under 1.5 s for a 5-page template (design §9).

## Pros and cons of the options
### Option 1 — poi-tl
- Good: typed `{{tags}}` that survive split runs; headers, footers, footnotes, table loops; Apache-2.0.
- Bad: release cadence; built against POI 5.2.2.
### Option 2 — docx4j with content controls
- Good: very active; data binding is a standard.
- Bad: admins need the Developer tab; repeating sections are Windows-only in practice; JAXB start-up cost.
### Option 3 — own engine on POI
- Good: full control, small (a few hundred lines), the same syntax.
- Bad: we own the run-merging edge cases.
### Option 4 — Document Builder
- Bad: seconds per fill, competes with editing on Community's single process, uncertain availability in Community 10.0.
### Option 5 — stamper libraries
- Bad: their expression languages can call code; unsafe with partly trusted authors.

## More information
Research R4 (`docs/plan/phase-3-memo-efile/epic-03-templates-notes-access/research.md`), ADR-0029 (files pipeline), ADR-0043 (type versions), ADR-0045 (conversion), ADR-0047 (ONLYOFFICE hardening).
