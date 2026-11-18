---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar (reported as a change from the overview)
---

# 0061 — Meeting papers and the one access rule

## Context and problem statement
Members of a committee must read the papers for each item, and nobody else may.
- An item or a paper can be limited to named people.
- A members-only (in camera) section excludes guests.
- Conflicted members will be excluded from an item's papers (E04).

The overview asked for one rule shared by the meeting page, downloads, search, notifications and, later, the board book. It also assumed that members would read the submitted memo through ADR-0050's reader table. ADR-0059 left "members gain access when the item is on a published agenda" to E03. Research: `phase-4-forums-meetings/epic-03-meetings-agenda/research.md §3.6, §4`.

## Decision drivers
- Least privilege: a committee reads the pack placed before it, not the whole memo file (note sheet, drafts, every version, annexes left out of the pack).
- One rule that E04 can narrow once and that every read path uses, with a leak test per path.
- What each revision carried is frozen, so a later replacement does not change what a member read.
- No second copy of documents: no extra storage, scanning or rendering, and no slow publication.
- Deny by default, and 404 for what is invisible (AGENTS §3.4).

## Considered options
1. **Members become memo readers** through more `FORUM` participant rows (the overview's assumption).
2. **Copy every paper into a meeting-owned file at publication.**
3. **A paper is a reference to a file, pinned to a version per revision, served by the meeting module after one SQL rule** (chosen).

## Decision outcome
Chosen option 3.

- **Papers:** `agenda_paper` refers to a file:
  - the memo's document (PDF rendition), an attachment or the final record, for submission items;
  - or a file of the new `meeting.paper` purpose, for papers the secretary uploads (record storage, PDF and text renditions at upload, reads audited).

  Each published revision records the file version it carried (`agenda_revision_paper.file_version_id`). Replacing a paper is a new file version in a new revision. The old version stays readable through the old revision, to those who could read it then.
- **The rule** (`PaperAccess`, published by the `meeting` module, written once as jOOQ conditions; the full statement is in `design.md §1.9`):
  - **Officers** of the forum (a secretary or the chair in office, or `forums.manage`) see the draft and every revision.
  - **Everyone else** sees an item of a published revision of a scheduled, in-progress or held meeting only if:
    - they are in the meeting's audience: a roster member on the meeting's date and still in office today; a guest; or an attendee of that item. Guests and attendees lose access when the minutes are approved;
    - members-only sections are for chair, vice-chair, members and secretary only;
    - the item's restriction, if any, names them.
  - **A paper** additionally needs the paper's restriction, if any, to name the person.
  - **Clearance** for the item's and the paper's classification applies to everyone, officers included.
  - **The E04 hook:** `PaperExclusions.excluded(user, item)` and `excludedFromPapers(user, item)` apply to everyone, officers included. E03 ships `NoExclusions`; E04 implements them from declarations.
  - **Titles:**
    - An item that someone in the audience cannot see shows its number and title with "papers restricted".
    - When the item says "hide the title", it shows only "Restricted item".
    - A restricted paper is not listed at all.
    - `forum.read` (oversight) sees unrestricted, unhidden titles and no papers.
- **Serving:**
  - `GET /meetings/{id}/papers/{pid}/content` checks the rule, then calls the files module's new `FileServing.serve` for the pinned version. Members get the PDF rendition; officers may also download the original.
  - The open is audited once (`meeting.paper.opened`, with `file.opened` context), with ranges and headers as for every file.
  - The `meeting.paper` purpose's `mayRead` is the same rule, so the generic files endpoints cannot bypass it.
- **Every surface uses the rule:**
  - the members' agenda, paper opens, "What changed" and the revision list;
  - per-recipient notifications (a person is told only of changes to items they may see; email carries no item titles);
  - the preview "as a member";
  - the cleared-titles view for `forum.read`.
  - **Search:** E03 adds no meeting content to search. When a later epic adds meetings or papers to the search function (ADR-0054 pattern), its table joins this rule's conditions.
- **Memo readers are unchanged:** committee members do not become readers of the memo. The `FORUM` reader rows of ADR-0059 remain for the chair and secretaries while a submission is open. This amends the reader part of ADR-0059's consequences ("members gain access when the item is on a published agenda"): members gain access to the item's **papers**, not to the memo.

### Consequences
- Good:
  - Least privilege.
  - One place for E04 to narrow.
  - The pinned versions say exactly what each person could read in each revision.
  - No duplicated storage or rendering, and publication stays fast.
- Good: the secretary can leave an annex out of the pack without touching the memo.
- Bad: a member who wants the memo's history (earlier rounds, note sheet) must be given memo access the ordinary way (a share, ADR-0050). This is deliberate: board packs carry papers, not working files.
- Bad: the files module gains a serving entry point for callers that decide access themselves. A wrong caller could bypass a purpose. It is used only by the meeting module, behind the rule, and an architecture test pins its callers.
- Follow-ups:
  - E04 implements `PaperExclusions` and runs the leak suite over every path in the surface registry.
  - Phase 5's book compiles per revision from the same rule and pinned versions, and adds per-user watermarking.

### Confirmation
- `PaperAccessIT`: a matrix of viewer kinds × item and paper states (published or draft, restricted, members only, hidden title, classification, cancelled meeting, former member, guest after the minutes are approved), checked through every surface in the registry.
- `PaperAccessArchTest`: no other class reads the revision tables, and only the meeting module calls `FileServing`.
- A leak-suite skeleton: for each surface, a user outside the rule sees no title (when hidden), paper, count or notification.
- The matrix `p4-e03-meetings.yaml` and cross-tenant tests.

## Pros and cons of the options
- **Option 1:** + reuses ADR-0050; − members read the whole memo file, the pack cannot leave annexes out, recusal must enter the memo rule, and access ends when the submission closes.
- **Option 2:** + clean access by purpose; − double storage, scanning and rendering, slow publication, and copies that can drift.
- **Option 3:** + least privilege, one rule, pinned versions, no copies; − a new files serving entry point for trusted callers.
