---
status: accepted
date: 2026-11-19
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar (the E06 screens go to the owner before build; one change from the overview reported: approval follows a fixed path per committee type, not the workflow engine)
---

# 0066 — Minutes: drafted from the committee's template, edited in ONLYOFFICE, approved on a fixed path, kept as a PDF/A record

## Context and problem statement
The overview (E06) asks for minutes drafted from a tenant .docx template filled from the agenda, attendance and decisions, edited in ONLYOFFICE (ADR-0047), approved through a workflow (chair review, member confirmation, or approval at the next meeting, per committee type), then locked and kept as a PDF/A record (ADR-0053). ADR-0062 requires that a recused member's minutes show the item's heading, their declaration and the decision, but not the discussion. Practice (CGI *Minute taking* §6, SS-1, BoardPro, OnBoard) is: the chair agrees the draft, members comment, the minutes are approved at the next meeting or by circulation, and the chair authenticates them (Companies Act 2006 s.249). Research: `phase-4-forums-meetings/epic-06-minutes-resolutions/research.md §3, §4, §6`.

## Decision drivers
- Secretaries finish minutes in Word; in-portal editors are criticised for formatting (research §4).
- The recused or restricted reader's copy must be made by rule, never by hand, and must not leak (ADR-0062, risk R1).
- Approved minutes are immutable, archival and provably unchanged (AGENTS §3.3, ADR-0053).
- Fewer moving parts for an on-prem install (AGENTS §7); no new library or service.
- Older directors: one place to read and act (the meeting page and "My meetings").

## Considered options
**A. The approval path**
1. **A fixed path in the minutes module, with the mode chosen on the committee type** (chosen).
2. The workflow engine with a `minutes` subject (the overview's plan).

**B. How the reader's copy is made**
1. **One locked content control per item in the draft; the copy replaces a control's content with a generated block** (chosen, proved by the T1 spike).
2. Item headings in a dedicated style mark where an item starts (fallback).
3. One document per item, joined at approval.

## Decision outcome
Chosen options A1 and B1.

### Drafting
- **The template** is part of the committee type version (maker-checker): a .docx uploaded and scanned by ADR-0048's engine against the **minutes vocabulary**, or the built-in default template when none is set. The vocabulary covers the meeting (committee, label, date, times, place, mode, chair), attendance (present in person and online, alternates and for whom, in attendance, apologies, absent, arrivals and departures by item, quorum lost and restored), and an **items block** repeated per agenda item (number, title, purpose, presenters, papers with "circulated" or "tabled", declarations, who left the room, the secretary's notes as "key points discussed", the outcome in capitals, resolution number and text, conditions, validity, vote, dissent with reasons, abstentions, took no part, quorum sentence, "to be ratified").
- **The engine gains block loops:** a `{{?items}}…{{/items}}` section over a list repeats its block per element with tags resolved against the element (poi-tl's documented behaviour); `TemplateData` gains blocks; the scanner checks block tags against the vocabulary. Memo templates are unaffected (their vocabulary has no block lists).
- **Generation** is a background job (`minutes-draft`): it fills the template from the latest published revision, `MeetingAttendance`, `Recusals.declarationsFor`, the decisions and notes, wraps each item's rendered block in a **block-level rich-text content control** tagged `memo-item:<itemId>` and locked against deletion, and stores the .docx as a new version of the minutes file (purpose `minutes.draft`, encrypted, ordinary storage). Regenerating one item replaces only that control's content; regenerating everything needs a confirmation ("Your edits will be replaced").
- **Editing:** the committee's secretaries (and `forums.manage` holders) edit the file in ONLYOFFICE as co-authors (ADR-0047) while the minutes are in DRAFT; a secretary recused from any minuted item cannot open the full file (403 `RECUSED`) and works through another secretary. Every save is a file version.
- **The structure check** runs on each saved version and before each step: every agenda item has exactly one control with its tag, and no tag is duplicated or unknown. A failure blocks the step with the item named ("The block for item 5 is missing. Use 'Restore item 5'"), which regenerates that item's control.

### The reader's copy
- A reader's **redaction set** is the minuted items they may not see in full under ADR-0061's rule with E04's recusals: recused, restricted to others, in a members-only section they do not read, or above their clearance.
- Their copy is the document with each control in the set replaced by a generated block:
  - recused: the item's heading (or "Item n — restricted" when its title is hidden), their own declaration, and the decision (outcome, number, text, conditions) as recorded, under "You declared an interest in this item and took no part";
  - otherwise not visible: the heading or "Item n — restricted", and "The record of this item is restricted".
- Copies are rendered to PDF (and to PDF/A at approval) by a background job, once per distinct redaction set (at most 50 per minutes; more is refused and alerted), and served by the minutes module with `Content-Disposition` and no download of the .docx. A reader with an empty set gets the full document. Every read of a copy is audited at document-open granularity.
- The secretary can **preview any reader's copy** before circulating.

### The approval path
```
DRAFT → CHAIR_REVIEW → CIRCULATED → (AT_NEXT_MEETING: approved by the next meeting's decision) → AWAITING_CHAIR → APPROVED
                                   → (BY_CIRCULATION: comment deadline passed or ended early)   → AWAITING_CHAIR → APPROVED
CHAIR_REVIEW → DRAFT (returned with comments); CIRCULATED → DRAFT (a decision corrected, or the secretary withdraws it)
```
- **Chair review:** the chair (the vice-chair when there is no chair in office or the chair is recused from any minuted item) reads their copy, may **suggest changes** in ONLYOFFICE's review mode (tracked changes the secretary accepts) when not recused, and either agrees ("Ready to circulate") or returns it with comments. The secretary may record the chair's agreement given outside the app, with how it was given (as E03's chair agreement).
- **Circulation:** the members in office of the meeting's roster and its alternates who attended read their copy (marked DRAFT on every page) and either say **"No comments"** or **"Suggest a correction"** (per item or general, 1–2,000 characters). Comments go to the secretaries and the chair, never to other members. The secretary adopts or declines each with a note, and may keep editing the document while it is circulated (the copies are rebuilt). The comment deadline is `circulationWorkingDays` (default 5) on the business calendar; silence is "no comments" (SS-1). Others may be asked for comments by name (CGI §6.1's technical review), without blocking.
- **Approval, per committee type (`minutes.approval`):**
  - **AT_NEXT_MEETING** (default for board-type presets): the minutes are linked to the next meeting's "Minutes of the previous meeting" item; its readers read their copy from there. The decision on that item (APPROVED, or APPROVED_WITH_CONDITIONS "approved subject to the amendments noted", or DEFERRED) approves them; the secretary then applies any amendments.
  - **BY_CIRCULATION** (default for management-committee presets): when the deadline has passed, or every member has answered, the minutes go to the chair. The next meeting's "Minutes of the previous meeting" item lists them for noting (CGI §6.3's ratification).
- **The chair's confirmation:** the chair of the approving meeting (AT_NEXT_MEETING) or the chair in office (BY_CIRCULATION), or the vice-chair when the chair is recused from any minuted item, confirms with a fresh step-up. It writes a **`minutes_approval`** row with ADR-0051's fields (authentication context, the .docx version and its SHA-256, the record hash chained to the previous approval of the committee's minutes) and is shown as an authenticated-approval stamp. Nobody confirms on the chair's behalf.
- **Then:** the meeting moves HELD → MINUTES_APPROVED (meeting module's published API), decisions become final, attendance and declarations freeze (ADR-0064, ADR-0062), and decisions waiting for ON_MINUTES_APPROVAL are released (ADR-0065). CLOSED is left to E07.

### The record
- The `minutes-record` job builds **one PDF/A-2u record**: a cover (committee, meeting label and date, approval mode, the approving meeting or the circulation, the chair's stamp, the .docx version and hash, renderer versions), the approved .docx converted by ONLYOFFICE (`outputtype: "pdfa"`), and an annex listing the meeting's resolutions with their numbers. It reuses ADR-0053's machinery, which the memo module publishes to the minutes module as `RecordPdfs` (as it publishes `ReportPages`); the machinery stays in the memo module.
- It is validated by veraPDF 1.30.3, stored as purpose `minutes.record` in the object-locked record bucket, locked until the meeting's date plus the committee type's retention years (ADR-0056 `retention`), or the tenant's default when the type sets none; locks are only ever extended (ADR-0029), and the runbook recommends a long retention for boards (CGI §8: "for the life of the organisation"; SS-1: "permanently"). Its SHA-256 is audited, and re-hashed by the weekly fixity job.
- **Redacted copies** of the approved minutes are built the same way, one per redaction set, as purpose `minutes.copy` (locked too), each naming the items redacted on its cover.
- **Classification:** the record takes the highest level of the meeting and its items; a reader below it reads their redacted copy.
- **As built (E06 T7):** the machinery is published as `RecordPdfs` (memo module); the record is versioned in `minutes_record`; the approved copies are a purpose of their own, `minutes.approved-copy` (record storage, locked; a purpose has one storage class), each a PDF/A with a cover naming its redacted items and the approval stamp on every page; a reader with an empty redaction set reads the record itself, so the record needs no classification of its own; the weekly fixity run reads both through the memo module's `RecordFixitySource` port. An item's actions are printed only inside its block, so a copy that redacts the item drops them.

### After approval
- **No edits.** A correction (CGI §6.2) is a `minutes_correction`: the item (optional), the corrected text, the reason, proposed by a secretary and approved by the chair with a step-up. It produces a **new record version**: the original record pages unchanged plus a "Corrections" annex; the earlier version stays locked and readable. The next meeting's "Matters arising" lists it.
- **Resolution extracts:** a secretary not recused from the item may issue a certified extract of one resolution (meeting, date, item heading, number, text, conditions, "minutes approved on …" or "minutes not yet approved"), as a PDF/A with the secretary's authenticated certification, stored as purpose `minutes.extract` and audited. Extracts are available once the decision is released (SS-1 allows certified copies before the minutes are entered when the text was before the meeting).

### Consequences
- Good: secretaries work in a real Word document from their committee's own template; directors see one copy, made for them, in one place.
- Good: the recused copy is made by rule from the same document; the leak suite covers every copy; the record is validated and locked.
- Good: no engine change; the path is the practice CGI and SS-1 describe.
- Bad: a tenant cannot add review steps to the approval path; named people can be asked for comments instead. If a customer needs a configurable chain, the engine can be added in front of chair review later.
- Bad: the content control is a dependency on ONLYOFFICE keeping tags (it does in 10.0.0.1, T1); a later ONLYOFFICE version is re-checked with the round trip before it is taken; the structure check and "Restore item n" contain a loss, and option B2 is the fallback.
- Bad: one rendering per distinct redaction set: a meeting with many conflicts makes several PDFs (capped at 50).

### Confirmation
- **T1 spike (2026-11-19): option B1 holds; the fallback B2 is not needed.** Details in the E06 log.
  - ONLYOFFICE 10.0.0.1, driven in the browser (`web/e2e/spike/minutes-controls.spec.ts`): nine block-level controls (`w:tag memo-item:<id>`, `w:alias`, `w:id`, `w:lock sdtLocked`) come back from the force save, the closing save and a save after reopening with every tag, title, id and lock. Typing inside a control and outside all controls works. Deleting a selection that spans a locked control, and select-all with Delete, delete nothing, in edit and in review mode. Backspace at a control's start edits the previous control's text but neither merges nor removes a control. Undo never removed a control (the controls are in the file when it opens; the forum report concerns controls a plugin adds in the session). The structure check and "Restore item n" stay as the safety net.
  - Review mode (`permissions.edit = false`, `review = true`) saves the chair's typing as `w:ins` and deletions as `w:del`, with the author's name and an extra `oouserid` attribute; the controls are kept.
  - Microsoft Word (golden file `templating/minutes-saved-by-word.docx`): it shows the nine controls as locked rich-text controls, refuses `ContentControl.Delete` ("This content control is locked and cannot be deleted") and a range delete around one, and keeps tags and locks through save and reopen.
  - Replacing item 5's control and converting with ONLYOFFICE to PDF and to PDF/A leaves no word of item 5's discussion in either text layer (`MinutesControlsRoundTripIT`).
  - poi-tl 1.12.2 repeats a block per element with nested sections and table-row loops; the controls are written by a streaming pass (XMLBeans moves were quadratic). A block with controls needs its start and end tags each in a body paragraph; T2's scanner reports it.
- `MinutesRedactionIT` and the recusal leak suite: canary strings in a recused item's notes, discussion and papers never appear in any copy, PDF text layer, notification or export for a recused reader; the full record contains them.
- State-machine tests for every allowed and forbidden minutes transition; matrix `p4-e06-minutes.yaml`; veraPDF validation of every generated record in CI; fixity job covers `minutes.record`.

## Pros and cons of the options
- **A1:** + fewer moving parts, practice-shaped, redaction per reader; − no tenant-configurable review chain. **A2:** + configurable, inbox tasks; − needs committee-role assignees, waits for another meeting and a comment window, and per-task redacted views in the engine.
- **B1:** + exact item boundaries, locked against deletion; − depends on ONLYOFFICE keeping tags (spike). **B2:** + nothing to lose; − a changed style merges items silently (caught by the check, but later). **B3:** + robust; − not one document, poor formatting across joins.

## More information
- CGI, *Minute taking* (2017) §6–§8: <https://cgi.org.uk/media/mpjeexxj/minute-taking.pdf> (accessed 2026-11-19).
- BoardPro minutes lifecycle: <https://help.boardpro.io/en/articles/16589602-understanding-the-meeting-minutes-lifecycle-draft-in-review-confirmed>.
- poi-tl sections over collections: <https://deepoove.com/poi-tl/>.
- Related: ADR-0047 (ONLYOFFICE), ADR-0048 (templates), ADR-0051 (authenticated approvals), ADR-0053 (PDF/A), ADR-0061 (paper access), ADR-0062 (recusal), ADR-0064 (attendance), ADR-0065 (decisions and resolutions).
