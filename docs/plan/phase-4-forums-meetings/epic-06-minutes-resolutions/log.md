# E06 Minutes and resolutions — log

## 2026-11-19
- **Plan written** (Phase 4 Q1: E06's screens go to the owner before build; the backend tasks do not wait):
  - research: R1 (no new library; poi-tl 1.12.2, POI 6.0.0, ONLYOFFICE 10.0.0.1 against 10.0.0 latest, PDFBox 3.0.8, veraPDF 1.30.3; Spring Boot 4.2 still RC2, re-check moved to E07), R5 from CGI's *Minute taking* guidance (read in full), the Companies Act 2006 ss.248–249, ICSI SS-1 through a practitioner summary, Basel 2015 ¶60, Bangladesh Bank's 4 August 2025 notice on dissent, and seven portals (Diligent Minutes, Boardvantage, OnBoard, Convene, iBabs, BoardPro, eScribe) with users' criticism from G2 and Capterra; R9 (numbering);
  - ADR-0065 (decisions and resolutions) and ADR-0066 (minutes: drafting, the approval path, the record);
  - design, threat model, tasks T1–T12, test plan, and flows AA–AI with nine recommendations for the owner (`ux-review.md`).
- **Choices that came from the research:**
  - **The decision is a structured record** with named dissent (optional reason), named abstentions, an optional vote and "took no part" derived from declarations and attendance: CGI §5.3.3–5.3.4, Basel ¶60, and Bangladesh Bank's requirement that dissent be "comprehensively recorded".
  - **Resolution numbers at the meeting's end, in agenda order:** no voids for corrections made in the room; the period is the meeting's date.
  - **Memos move on when the meeting is marked as held by default**, with "when the minutes are approved" per committee type: s.249 makes minutes the evidence of a decision taken at the meeting; SS-1 allows certified extracts before the minutes are signed.
  - **Two approval modes per committee type:** at the next meeting (CGI §6.3: "virtually all organisations") or by circulation then noted (CGI §6.3's ratification; SS-1's comment window where silence is consent).
  - **Chair review before circulation** (CGI §6) and a comment box for members rather than co-editing (OnBoard users email revised versions; BoardPro keeps editing during review).
  - **Approved minutes are never edited:** corrections annexed in a new record version (CGI §6.2; BoardPro locks confirmed minutes).
  - **The secretary's notes are not part of the record** (CGI §8.1: notes are discoverable and usually destroyed after approval; here they are kept, never deleted, and visible only to secretaries).
- **Changes from the overview, recorded in ADR-0065 and ADR-0066, to report to the owner:**
  - numbering shares the format code (`common.numbering`), not one counter service: resolutions get their own counter, as meeting numbers already do, and no memo number or table changes (overview risk R8 avoided);
  - minutes approval follows a fixed path per committee type, not the workflow engine: the next-meeting step is another meeting's decision, the comment window is not a task, and each reader needs their own redacted copy, which the engine cannot give.
- **Found while reading:**
  - `ForumSubmissions.decide` is in `forum.internal`; T2 publishes `ForumDecisions` (decide, defer at a meeting, refer at a meeting).
  - `WorkflowForums.decided` takes only a remark; T2 adds `details` so the note sheet can link the resolution without a memo migration.
  - The PDF/A pipeline is internal to the memo module; T7 publishes `RecordPdfs` as `ReportPages` already is.
  - `templating` supports sections and table-row loops but not repeated blocks; T2 adds block loops (poi-tl's documented behaviour) without changing memo templates.
  - The meeting module cannot depend on minutes; E04's "declarations on a decided item change only by correction" goes through a `DecidedItems` port the minutes module implements, as E03's `PaperExclusions` hook did.
- **Uncertain until T1:** whether ONLYOFFICE 10.0.0.1 keeps a locked, tagged block-level content control through editing, undo and save. A forum report says Ctrl+Z can remove a plugin-added locked control; the structure check catches it and "Restore item n" fixes it. If tags are dropped, the heading-style fallback (ADR-0066 option B2) is used.
- **Migrations:** E06 adds `forum/V7` and `minutes/V1`–`V4`, and no meeting migration (`meeting/V5`–`V7` are in open PRs #181, #185, #188; `V8`, `V9` reserved for E05 T3, T4).
- **Waiting for the owner:** the review of flows AA–AI and the nine recommendations (T10 and T11 wait for it). No real-world input is needed.
