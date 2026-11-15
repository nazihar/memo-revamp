# E03 Templates, note sheet and access — log

## 2026-11-15 — Plan
- **Research:** R4 (templating) and R9 (rich text) plus industry practice (D-Nothi, NIC e-Office, Microsoft) by two research agents, checked by the lead. The lead confirmed poi-tl's release state and its POI 5.2.2 dependency on Maven Central, so T1 starts with a spike.
- **ADRs (proposed until the owner's review of the screen flows):**
  - ADR-0048 templates;
  - ADR-0049 note sheet;
  - ADR-0050 access model and copy-to;
  - ADR-0051 authenticated approvals.
- **Plan:** design, threat model, tasks T1–T9 and test plan written.
- **Waiting for the owner:** flows C (note sheet) and D (sharing and copy-to) in `ux-review.md`, with six questions and recommended answers. The backend tasks T1–T6 do not depend on the answers except for defaults (copy timing, comments by copies), which are settings.

## 2026-11-15 — Owner review of flows C and D
- **Owner:** "approved as recommended". Recorded in `ux-review.md` (Owner feedback).
- **ADRs:** 0049, 0050 and 0051 accepted. ADR-0048 stays proposed until the T1 spike chooses the engine.
- **Build starts:** T1 (template engine spike) first.

## 2026-11-15 — T1 template engine spike, scanner and fill
- **Spike** (a scratch project with two generated letterheads; tags split across runs with different formatting, a header, a footer, the approval loop row, and a hostile variant):

  | | POI 5.5.1 | POI 6.0.0 |
  |---|---|---|
  | Split runs, header, footer, loop row | pass | pass |
  | SpEL-looking tag `{{T(java.lang.Runtime)…}}` | left as text | left as text |
  | Fill p50 / p95 (200 fills, dev machine) | 13 / 19 ms | 11 / 17 ms |
  | Without Batik | — | pass |

  **Decision:** poi-tl 1.12.2 on POI 6.0.0 (ADR-0048 accepted).
- **Findings that shaped the code:**
  - inside a `{{?x}}` block poi-tl makes the block's value the root, and an empty string still shows the block;
  - so the filler renames section tags to `?x` (both ends, so poi-tl still pairs them) and answers them with a flag from our own lookup, which also replaces poi-tl's dot-path engine;
  - a `{{tag}}` inside the loop's template row breaks the fill, so the check reports `TAG_IN_LOOP_ROW`;
  - `{{@…}}` with a text value would be read as a picture source, so `@ # * +` are refused at fill as well as at upload.
- **Built:**
  - a new Modulith module `templating`: the scanner (commons-compress and hardened StAX, no POI); refusal rules with part names; the tag inventory with part, paragraph, table row and excerpt; the vocabulary check with suggestions; `PoiTlFiller` (2 threads, queue 8, 5 s, POI zip limits lowered);
  - the files hook `FilePurpose.inspectContent` and the reason `CONTENT_REFUSED` with `refusals` in the problem and the audit;
  - the purpose `memo.template` (.docx, 10 MiB, records; uploads only for a draft type version by `memo.types.manage`);
  - `MemoTemplateTags` (the published tags);
  - ArchUnit rules (poi-tl only in `templating`; no SpEL);
  - `com.deepoove.poi` logging at WARN.
- **Deviations, recorded in ADR-0048:**
  - `.dotx` is refused by the existing type check for now; carried;
  - a package that passes every rule is stored as uploaded rather than re-serialised; fills are POI-written.
