---
status: accepted
date: 2026-11-15
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0052 — Generated pages: one rendering interface, PDFBox with an embedded font for English

## Context and problem statement
The final rendition (ADR-0053) needs pages that no user wrote:
- a cover with the memo's identity;
- the note sheet by round;
- the approval stamps;
- placeholders for attachments that cannot be converted.

Later features need the same pages: a printable note sheet, and E06's import report. Phase 3 is English only (§10 Q3). A later customer may need Bengali, Arabic or Hindi, which need complex-script shaping. How do we draw these pages now without closing that door?

## Decision drivers
- PDF/A-2u output: embedded fonts with Unicode mappings, no external resources.
- Deterministic output, so render-and-compare golden tests are stable.
- No new service to run for English, and a permissive licence (AGENTS §3).
- A clear seam where a shaping engine can plug in later.
- Text from users (subjects, notes, names) drawn as text, never interpreted as markup.

## Considered options
1. **Our own layout on PDFBox 3.0.8** with Noto Sans (OFL) TrueType, behind a `PageRenderer` interface.
2. **openhtmltopdf** (LGPL, on PDFBox), with HTML templates for the pages.
3. **Apache FOP** (XSL-FO).
4. **A HarfBuzz-based sidecar now** (Typst or WeasyPrint).
5. **ONLYOFFICE Document Builder** (a JS script that writes DOCX, then ConvertService to PDF/A).

## Decision outcome
**Option 1 now. Options 4 and 5 are the recorded paths for complex scripts, behind the same interface.**

- **Interface:**
  - The `rendering` package of the memo module declares `PageRenderer.render(pages: List<PageSpec>, out)`.
  - `PageSpec` is a small typed model with no markup: blocks of headings, paragraphs, key–value tables, round sections and stamp boxes, each holding plain strings.
  - Callers build `PageSpec`s and never draw.
- **English implementation:**
  - PDFBox 3.0.8 with Noto Sans Regular and Bold (TTF builds, OFL-1.1, from NotoSans-v2.015), embedded as subsets with `PDType0Font`. The fonts ship as resources with their licence.
  - A layout helper does greedy word wrapping on measured widths, page breaks with a repeated header and a page footer, simple tables, and keep-together for stamp boxes.
  - A4 portrait, 20 mm margins.
  - No hyphenation. Long unbroken strings, such as hashes, break at any character.
- **Text handling:**
  - Every string is drawn as given, after removing control characters.
  - A character the font lacks is drawn as U+FFFD and counted. The rendition records that count, and the golden tests fail on it.
- **Complex scripts later:**
  - A second implementation can replace this one per tenant locale:
    - ONLYOFFICE Document Builder → DOCX → ConvertService `pdfa`, which reuses a service we run; whether the Community edition includes it is unconfirmed;
    - or a Typst or WeasyPrint sidecar with HarfBuzz shaping.
  - The interface takes the page model, not PDFBox objects, so either can implement it.

### Consequences
- Good: no new runtime component; byte-stable output under a fixed clock, so the golden tests are cheap.
- Good: user text cannot inject layout or links; there is no HTML anywhere in the path.
- Bad: we own a small layout engine (wrapping, tables, page breaks). It is kept small on purpose; anything richer goes through the interface to another engine.
- Bad: no complex scripts until a customer needs them. The U+FFFD count makes a gap visible rather than silent.
- Neutral: about 1 MB of fonts in the image.

### Confirmation
- Golden render-and-compare tests (TESTING_STRATEGY §3.8) with English names, long remarks, a hash and a 30-step approval history. The pages are rendered to PNG at 100 dpi and compared within a small tolerance.
- Text extraction returns every string in order.
- A property test with random Unicode strings: no exception, and the U+FFFD count is correct for characters outside the font.
- veraPDF 2u validation of a document made only of generated pages.

## Pros and cons of the options
### Option 1 — PDFBox and our own layout
- Good: already a dependency; full control of PDF/A details; deterministic.
- Bad: we write wrapping and tables.
### Option 2 — openhtmltopdf
- Good: HTML and CSS templates; PDF/A support built in.
- Bad: about 70 releases in five months (churn); LGPL; Arabic only through presentation forms, no Indic; HTML templates invite markup injection mistakes.
### Option 3 — Apache FOP
- Good: mature, Apache-2.0.
- Bad: heavy for four page types; PDF/A-2 implementation level not stated; partial complex scripts.
### Option 4 — HarfBuzz sidecar now
- Good: real shaping for every script.
- Bad: a new service to deploy, secure and monitor, for a need Phase 3 does not have.
### Option 5 — ONLYOFFICE Document Builder
- Good: reuses ONLYOFFICE; good shaping.
- Bad: Community availability unconfirmed; a JS script per page type; output PDF/A only through a second conversion.

## More information
- Research: `docs/plan/phase-3-memo-efile/epic-04-final-rendition/research.md` (R3).
- PDFBox fonts: https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.8/org/apache/pdfbox/pdmodel/font/PDType0Font.html (accessed 2026-11-15).
- Noto Sans: https://github.com/notofonts/latin-greek-cyrillic (accessed 2026-11-15).
- Related: ADR-0053 (final rendition), ADR-0049 (note format rendered here), ADR-0051 (stamps).
