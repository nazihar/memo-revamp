# E03 Meetings and the agenda builder — research

> Step 1 of `AGENTS.md §5`. Accessed 2026-11-18 unless stated. Phase research items covered here: R1 (versions: dnd-kit, time-zone data, Spring Boot 4.2) and R2 (agenda and meeting practice). Sources marked **[snippet]** were read only from a search result, because the site blocked automated reads. OnBoard and eScribe help articles were read through their public Zendesk article API; the normal article URLs are given.

## 1. Starting point (code, 2026-11-18, `master` plus the E02 branches up to `phase4/e02-t5`)
- **Modules:**
  - `com.memo.meeting` exists as an empty Spring Modulith module (`backend/src/main/java/com/memo/meeting/package-info.java`). `AGENTS.md §4` lists `meeting` as its own bounded context, and the phase overview says Phase 4 fills it.
  - `com.memo.forum` holds forum types, forums, members (E01) and submissions (E02). Its migrations run to `forum/V4__submission_policy.sql` on `phase4/e02-t5`.
  - Each module's migrations live in `db/migration/<module>` with their own history table, run in module dependency order (ADR-0007). The module order is precomputed at build time (#151), so a new module with migrations must be added to that metadata.
- **What the forum module publishes today:** `ForumDirectory` (`forum`, `forumByKey`, `membersOn(forumId, date)`, `rolesOf`, `isSecretary`), `ForumPolicy.kt` (roles, outcome kinds, `SubmissionPolicy` with `LateMode` ALLOW, BLOCK or CHAIR_APPROVAL), and `Quorum`.
  - The events `ForumChanged`, `ForumTypePublished` and `ForumSubmissionChanged` (whose comment says "E03 listens to place accepted items on agendas") are declared in `com.memo.forum.internal`. Another module cannot listen to them until they move to the published package.
  - The type version's policy (including the submission policy) is read only inside the module (`ForumTypeVersionStore`).
- **Submissions (E02):** `forum_submission` with `subject_type` and `subject_id` (a memo), purpose DECISION, DISCUSSION or INFORMATION, `resolution_sought`, `presenter_id`, status QUEUED → ACCEPTED → DECIDED and the closing statuses, and an optional `target_date`. Accept takes a target date, not a meeting, because meetings did not exist.
- **Memo readers (E02 T3, PR #161):** `MemoReaders.syncForum` keeps the memo's `FORUM` participant rows equal to the chair and secretaries in office of every forum with an open submission of the memo. `MemoReaders.readable(ids)` returns the number and subject of memos the actor reads. ADR-0059 says "members gain access when the item is on a published agenda (E03)", without saying how.
- **Files:** every file has a purpose that decides who uploads and reads (`FilePurpose.mayRead(file)`), records are object-locked, renditions (PDF, text) are built at upload (ADR-0045), and downloads stream through `/api/v1/files/{fileId}/versions/{versionId}/content` and `…/renditions/pdf` with HTTP ranges. `mayRead` sees the file, not the version. The memo's purposes (`memo.document`, `memo.attachment`, `memo.rendition`) allow reading to whoever reads the memo.
- **While a memo waits at the forum step its content is locked:** the memo is in review, so its document and attachments take no new versions (ADR-0046, ADR-0058). What the committee reads cannot change under it.
- **Calendar:** `WorkingDays.since(start)` gives working days elapsed on the tenant's business calendar (ADR-0041). There is no "n working days before a date" yet.
- **Notifications:** `Notifications.notify(recipient, kind, params, link)` in the producer's transaction; subjects take no parameters (ADR-0030).
- **Web:** React 19.3, TanStack Router, Query, Form and Table, `radix-ui` 1.8.1 and `@xyflow/react` 12.12.1 for the workflow builder. **No dnd-kit package is installed yet.** The workflow builder uses React Flow, not dnd-kit.
- **Demo:** six committees (E01 T6), `secretary@demo.example` as secretary of the BRC and MCC, and the credit workflow escalating to the MCC with `wait: true` (E02 T5).

## 2. Versions (R1)
| Item | Finding | Source |
|---|---|---|
| `@dnd-kit/core` | 6.3.1, released 2024-12-05, MIT; peer `react >= 16.8` | <https://registry.npmjs.org/@dnd-kit/core> |
| `@dnd-kit/sortable` | 10.0.0, released 2024-12-04, MIT; peer `@dnd-kit/core ^6.3.0` | <https://registry.npmjs.org/@dnd-kit/sortable> |
| `@dnd-kit/utilities` | 3.2.2, released 2023-11-06, MIT | <https://registry.npmjs.org/@dnd-kit/utilities> |
| `@dnd-kit/react` (new line) | 0.5.3, released 2026-10-31, MIT, pre-1.0 (0.5.0 June 2026, 0.4.0 April 2026); peer React 18 or 19 | <https://registry.npmjs.org/@dnd-kit/react> |
| Repository | MIT, not archived, about 17.8k stars, 162 open issues, last push 2026-10-31. Active work is on the 0.x framework-agnostic line; the legacy `core` and `sortable` line has had no release for about 23 months | <https://github.com/clauderic/dnd-kit> |
| Spring Boot | 4.1.2 is the current release on the project page; 4.2.0 is not GA (RC1 reported in October 2026, GA expected in November). The backend stays on 4.1.x for E03 and re-checks at E05 | <https://spring.io/projects/spring-boot>, <https://infoq.com/news/2026/10/spring-news-roundup-oct19-2026> **[snippet]** |
| IANA time-zone database | Latest release 2026f (2026-10-27). The JVM carries its own copy, refreshed with JDK updates in the base image (ADR-0012) | <https://www.iana.org/time-zones> |

**dnd-kit choice:**
- **Options:**
  1. The legacy `@dnd-kit/core` 6.3.1 + `@dnd-kit/sortable` 10.0.0 + `@dnd-kit/utilities` 3.2.2: stable 1.x-style API, a keyboard sensor with `sortableKeyboardCoordinates`, screen-reader instructions and live-region announcements that can be translated (<https://dndkit.com/guides/accessibility>).
  2. The new `@dnd-kit/react` 0.5.3: actively developed, accessibility plugin on by default (<https://dndkit.com/extend/plugins/accessibility>), but pre-1.0, with API changes between minor versions. Its keyboard sensor moves by 10 pixels per arrow key (<https://dndkit.com/extend/sensors/keyboard-sensor>), which suits free placement, not a list.
- **Recommendation: option 1, pinned.** `AGENTS.md §3.1` rejects pre-release libraries unless pinned and justified, and nothing here needs the new line. The risk is the 23 months without a release. It is contained:
  - every move also exists as an explicit command (Move up, Move down, Move to…) that calls the same API, so dragging is a convenience layer that can be swapped;
  - the drag layer lives in one component;
  - T7 starts with a spike under React 19.3 in StrictMode.
  - Moving to `@dnd-kit/react` is re-checked when it reaches 1.0.
- The dependency register gets three rows in T7.

## 3. Industry practice (R2)

### 3.1 Agenda structure
| Product | Structure | Source |
|---|---|---|
| Diligent Boards | Topics (book tabs), subtopics up to 3 levels, plus unnumbered breaks and headings; numbering can be turned off per tab | <https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/agenda-builder/adding-agenda-items-bwa.htm>, <https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/books/building-the-book-structure-bwa.htm> |
| OnBoard | Sections and sub-sections up to 4 levels; per-section "Remove Section Numbering" | <https://help.passageways.com/hc/en-us/articles/360038266971> |
| BoardPro | Two levels, sections and items; the first and last sections cannot be deleted | <https://help.boardpro.io/agendas/the-agenda> |
| Admincontrol | Items and sub-items; a "break" does not count in the numbering; starting number, "continue from previous" and manual IDs | <https://admincontrol.com/en/help/board-management-solution/create-agenda-items>, <https://admincontrol.com/en/help/board-management-solution/agenda-numbering> |
| Diligent Community | Section types Standard, **Consent**, **Member-only** and Public comment | <https://help.diligentoneplatform.com/helpdocs/d1p/en-us/Content/community/community_admin_guide/community-getting-started.htm> |
| eScribe | Categories (parents) and items (children) in a template | <https://help.escribemeetings.com/hc/en-us/articles/41384648990221> |

**Taken from this:** three levels are enough and easier to read than four: **sections** (headings such as "For decision", "Consent items"), **items**, and one level of **sub-items**. Items are numbered continuously through the meeting (1, 2, 3 …; sub-items 4.1, 4.2) and sections are unnumbered headings. Breaks are items that take time and no number. Numbers are recomputed only when a revision is published (§3.5), so members never see numbers shift under them.

### 3.2 Standing items, templates and cloning
- **eScribe:** a meeting template has a profile and an agenda structure with standing items; changes to the structure "only apply to a new meeting being created" (<https://help.escribemeetings.com/hc/en-us/articles/41384638700045>).
- **BoardPro:** "Build Agenda" starts from a clone, a best-practice template or a strategic template. Cloning copies sections, items, presenters, purposes and times, but not documents or minutes (<https://help.boardpro.io/en/articles/11087212-building-an-agenda-new-ui>, <https://help.boardpro.io/en/articles/11083684-clone-copy-an-agenda> **[snippet]**). "Smart" items link the previous minutes, the interests register and the action list (<https://help.boardpro.io/en/articles/11202564-introduction-to-smart-agenda-items> **[snippet]**).
- **OnBoard:** duplicating a meeting copies the structure, section members and permissions; "Documents attached to agenda sections do not copy" (<https://help.passageways.com/hc/en-us/articles/360037898832>). A community post says it "does not have agenda templates" (<https://help.passageways.com/hc/en-us/community/posts/35343322886285> **[snippet]**).
- **Admincontrol:** "All content in the selected agenda, except related documents, can be reused" (<https://admincontrol.com/en/help/board-management-solution/build-your-digital-agenda>).
- **Boardable:** scratch, an agenda template, or "Start with last meeting's agenda" (<https://docs.boardable.com/knowledge/agenda-build-and-edit>).
- **Diligent Boards:** "Copy book" copies the tab structure, optionally the documents, and the permissions (<https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/books/copying-an-existing-book-bwa.htm>). A reviewer: the auto-agenda "won't allow us to use our own templates" (<https://www.capterra.com/p/149069/Diligent-Boards/reviews/> **[snippet]**).
- **Practice:** standing items are apologies, declarations of interest, the previous minutes and matters arising. NCVO asks for new declarations "at the beginning of every meeting" (<https://www.ncvo.org.uk/help-and-guidance/governance/making-decisions-as-a-board/running-good-meetings>). AICD lists "director conflict of interest disclosures" as a standing item (<https://aicd.com.au/board-of-directors/meeting/agenda/board-meeting-agenda.html>). Emirates NBD's audit committee lists "Minutes of the previous meeting" as a standing item (<https://www.emiratesnbd.com/-/media/enbd/files/Sustainability/enbd_bac_tor.pdf>, §4.1.3).
- **Any other business:** the CGI-hosted blog "Who owns the agenda?" says "disallowing AOB can be a highly effective way of helping a board meeting achieve its purpose", and if kept, to raise it at the start with about 5 minutes (<https://cgi.org.uk/blog/who-owns-the-agenda>). NCVO lets chairs ask for advance notice of AOB.

**Taken from this:**
- **The committee type's agenda template** (sections and standing items with default purposes and times) is part of the reviewed type version. A meeting copies it when its agenda starts; later template changes affect only new agendas (eScribe).
- **"Start from an earlier meeting"** copies sections, items, titles, purposes, times, presenters and restrictions. It never copies papers, submissions, declarations or requests (BoardPro, OnBoard, Admincontrol all leave documents behind).
- **Standing items have kinds** the system understands: APOLOGIES, DECLARATIONS, PREVIOUS_MINUTES, MATTERS_ARISING and AOB. Later epics fill them: attendance (E05), declarations (E04), the previous minutes (E06) and open actions (E07), as BoardPro's smart items do.
- **AOB is in the presets but can be removed**, and its hint asks for notice in advance. Where it goes (start or end) is the template's choice.

### 3.3 Consent agendas
- **Practice:**
  - BoardSource: a consent agenda "groups routine, non-controversial items into a single package for one combined vote". Any member may ask for an item to be "removed from the consent agenda and discussed separately" before the vote. "Financial statements, for example, should not be included" (<https://boardsource.org/resources/consent-agendas/>).
  - Board Intelligence: "Allow any board member to pull an item from the consent agenda for separate discussion"; "if no one objects, the items are passed under unanimous consent"; minutes "show exactly which matters were adopted without debate"; never for matters of "significant legal, financial, or reputational risk" (<https://www.boardintelligence.com/blog/what-is-a-consent-agenda>).
  - Pulling an item needs no reason and no vote (<https://www.onboardmeetings.com/blog/consent-agenda/> **[snippet]**). A pulled item is taken straight after the consent agenda or later in the meeting (League of Women Voters handout, <https://my.lwv.org/sites/default/files/leagues/wysiwyg/%5Bcurrent-user%3Aog-user-node%3A1%3Atitle%5D/the_consent_agenda_aka_consent_calendar_rev_0.pdf> **[snippet]**).
  - AICD's starring system plays the same role: "Un-starred items are approved without discussion, according to the draft resolution provided" (<https://aicd.com.au/board-of-directors/meeting/agenda/board-meeting-agenda.html>).
- **Products:**
  - eScribe models a consent parent item with sub-items; lifting an item is a clerk's action and "the order of the lifted item will drop to the bottom" (<https://help.escribemeetings.com/hc/en-us/articles/41683652751629-Managing-a-Consent-Vote-in-Agenda-and-Minutes>).
  - Diligent Community has a "Consent" section type.
  - OnBoard's blog says directors "flag items they want to discuss further" through comments (<https://www.onboardmeetings.com/blog/consent-agenda/>).
  - **No product documents a member's own request to lift an item inside the software.**

**Taken from this:**
- A **consent section** is a section marked "consent". Its items are taken together by one decision (E06 records it), each with its own draft resolution or "to note".
- **Any member in the audience can press "Ask to discuss"** on a consent item until the meeting starts. That is enough: no reason, no approval. The item shows "Lifted for discussion at <name>'s request" to everyone at once, and it is taken straight after the consent section unless the secretary moves it in the next revision. The member can withdraw their own request before the meeting.
- The builder warns, but does not block, when an item "for decision" without a resolution sought is put into a consent section, and its help text repeats BoardSource's caution about financial statements.

### 3.4 Times, presenters and purpose
- **Times:** Diligent computes start and end times from each item's duration and says durations "should be used for all agenda items" (<https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/books/adding-and-managing-agenda-items-bwa.htm>). OnBoard computes start times and draws an "Agenda Pacer Line" (<https://help.passageways.com/hc/en-us/articles/360038266971>). Admincontrol: "It is not possible to allocate time for subitems" (<https://admincontrol.com/en/help/board-management-solution/create-agenda-items>). A BoardEffect reviewer: "We are not able to put in the time expected to give to each item" (<https://www.capterra.com/p/151082/BoardEffect/reviews> **[snippet]**). The CGI-hosted blog: "Add approximate timings for each agenda item".
- **Presenters:** a user or a typed-in guest (OnBoard, Admincontrol); several presenters (BoardPro <https://help.boardpro.io/en/articles/11084428-agenda-items>, eScribe sponsors <https://help.escribemeetings.com/hc/en-us/articles/41384356866445>).
- **Purpose:** BoardPro has discussion, information and decision. AICD asks whether each item is for "decision, discussion, noting (formal acknowledgment), or an information session". CGI with Board Intelligence, *Effective board reporting* (July 2026): the agenda and papers should "make clear what action or input is needed from the board", and the ask should be explicit, "what it must decide, discuss, or note" (pp. 12, 15, 26, <https://www.cgi.org.uk/media/kagbmcd3/cgi_boardreporting_17july.pdf>).
- **Order:** "Chairs should also position items in the running order to reduce unnecessary cognitive load" (same CGI report, p. 19).

**Taken from this:** each item has a duration in minutes, and its start time is computed from the meeting's start. Sub-items take part of their item's time and have none of their own. Section totals and the meeting's total are shown, with a warning when the agenda runs past the planned end. Each item has a purpose (for decision, for discussion, for noting) shown as a word, not only a colour, and the resolution sought from the submission's cover sheet. Presenters are users or typed names, one or more.

### 3.5 Papers, publishing, late papers and changes after publishing
- **Paper deadline:** "no less than five working days before the board meeting except in exceptional circumstances". Only 48 % of organisations always meet it, and the average pack was 220 pages in 2025. The report recommends a deadline for quality assurance and one for the board, a "system to track progress and chase late submissions", and notes that some boards reject late papers, "a stance that is easier to take with the support of the chair" (CGI with Board Intelligence, July 2026, pp. 4–5, 21, 23, 26).
- **Bank terms of reference:**
  - Bank of Cyprus, Group Board Audit Committee: notice "together with the agenda and support material" no later than five working days before (§5.2); invitees attend "only for a specific item(s) on the agenda and leave the meeting immediately afterwards" (§5.7) (<https://www.bankofcyprus.com/contentassets/dfbb07c97a924b9ca6a1710a71f958fb/group-audit-committee_terms-of-reference.pdf>).
  - Emirates NBD, Board Audit Committee: "The Committee's Secretary shall develop the agenda for each Committee meeting for approval by the Chairman" (§4.1.3); papers at least seven days in advance "unless otherwise approved by a majority decision" (§4.1.4); meetings by telephone or video (§4.1.1) (<https://www.emiratesnbd.com/-/media/enbd/files/Sustainability/enbd_bac_tor.pdf>).
  - HSBC, Group Risk Committee: the chair ensures the committee "has the information it requires" (§5) (<https://www.hsbc.com/-/files/hsbc/investors/results-and-announcements/stock-exchange-announcements/2026/september/sea-280926-e-terms-of-reference-of-group-risk-committee.pdf>).
  - E02 already cites Exim Bank Malaysia and the Bank of England (five and two working days).
- **Who owns the agenda:** "The chair. Many things can be delegated but not this one" (CGI-hosted blog). The UK Code's Principle F: the chair "ensures that directors receive accurate, timely and clear information" (<https://www.frc.org.uk/docs/6709/html/>). NCVO: the chair sets the agenda with the chief executive's support.
- **Late items:** "at the discretion of the Chair… Papers should only be tabled at the meeting in exceptional circumstances" (Loughborough, <https://lboro.ac.uk/committees/handbook/6-preparation-of-papers> **[snippet]**). Late items "clearly labeled on the agenda and in the minutes as a 'Late Item'" (University System of New Hampshire, <https://www.usnh.edu/policy/bot/i-governance-policies/f-submission-agenda-items> **[snippet]**).
- **Products after publishing:**
  - Boardable: edits after publishing stay in draft and "Attendees continue to see the previously published agenda" until it is republished; the notify option is on by default (<https://docs.boardable.com/knowledge/how-to-publish-meeting-materials>).
  - BoardPro: edits stay invisible until republished, with an "Agenda Change Log" (<https://help.boardpro.io/en/articles/11361926-edit-a-published-agenda-new-ui>).
  - eScribe: after approval, late or changed items go into an **Addendum**, and a **Revised Agenda** merges both (<https://help.escribemeetings.com/hc/en-us/articles/41397486559629>).
  - Diligent Boards: approving an updated agenda has a "Send notification" toggle, and "Only one email is sent per user per site within a 2-hour window"; a replaced document can be "Marked as Revised" into the directors' "Update History" (<https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/books/approving-and-unapproving-documents-bwa.htm>, <https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/books/replacing-documents-bwa.htm>).
  - OnBoard: no automatic emails on change; a manual reminder can include "Changes Since Your Last Review" (<https://help.passageways.com/hc/en-us/articles/33559277046797>, <https://help.passageways.com/hc/en-us/articles/360040039292>).
  - Admincontrol: draft (watermarked) or published, publishing separate from notifying, and replacing an attachment drops annotations (<https://admincontrol.com/en/help/board-management-solution/publish-the-meeting-and-notify-users>).
  - A Boardvantage customer quote describes recompiling a 750-page book after a late change (<https://www.nasdaq.com/en-gb/products/governance/boardvantage> **[snippet]**).
- **Approval before publishing:** Boardable: "Meetings cannot be published until all required approvals are complete" (<https://docs.boardable.com/knowledge/use-agenda-approval-workflows>).

**Taken from this:**
- **A working draft and published revisions.** The secretary edits a draft. Publishing makes an immutable revision (1, 2, 3 …), and members always read the latest one. Nothing the secretary does in between is visible to members (Boardable, BoardPro).
- **Each later revision says what changed:** item added, removed, moved (with the old number), retitled, paper added, paper replaced, late, or tabled at the meeting. It also carries the secretary's one-line note. Members see "What changed since you last looked" (OnBoard), computed per person and limited to what they may see. This is the eScribe addendum without a second document.
- **Notify, or not.** Publishing notifies the audience by default. The secretary can turn it off for a minor correction (Diligent, Admincontrol, Boardable).
- **The chair agrees the agenda** before the first publication when the committee type says so (default on: CGI, Emirates NBD §4.1.3, Boardable). The chair agrees in the app, or the secretary records "agreed by the chair by email on …" on their behalf, recorded as such. Later revisions need no new agreement: late papers have their own rule.
- **The paper deadline** is the meeting date minus the type's `deadlineWorkingDays` (default 5) on the business calendar. The builder shows it, and the secretaries are reminded once if the agenda is not published by then (CGI's "chase late submissions").
- **Late papers** (an item or paper first published after the deadline) follow the type's late mode from E02:
  - ALLOW: published and marked "Late paper".
  - BLOCK: cannot be placed on this meeting.
  - CHAIR_APPROVAL (default): held back until the chair agrees, then published and marked "Late paper". Lateness is approved separately from content (eScribe's late-exception workflow, E02 research).
  - During the meeting the secretary can add a paper "tabled at the meeting", marked so.
- **Papers are pinned file versions.** A revision records exactly which version of each paper it carried. A replacement is a new version in a new revision, flagged "paper replaced", and the old version stays readable as part of the old revision. Members read the PDF rendition. Page counts per paper and per pack are shown to the secretary, because packs are long (220 pages on average).

### 3.6 Item-level and document-level access, and conflicted members
- **Products:**
  - OnBoard: per-section roles Admin, Contributor, Reader or **Exclude** ("Cannot view agenda section details or documents"), inherited by sub-sections; Meeting Admins cannot be excluded (<https://help.passageways.com/hc/en-us/articles/360038266671>).
  - Diligent Boards: per document "Show document to selected users" or "Hide document from selected users", with a preview of current and future access (<https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/books/managing-document-visibility-bwa.htm>). Reviewers: permissions "need to be redone" when a new version is uploaded (Capterra, above, **[snippet]**).
  - Admincontrol: folder restriction for "conflict of interest or discussion on remuneration"; "The restricted users will only see the agenda item, but without attachments", so **the title stays visible** (<https://admincontrol.com/en/help/board-management-solution/folder-access-permissions>).
  - Diligent Community: member-only sections, and a template switch "Show member only item titles on public agenda documents".
  - eScribe: closed sessions limited to attendees with "Allow in Closed Sessions" (<https://help.escribemeetings.com/hc/en-us/articles/41397350209549>); conflicts declared per participant per item, with no notifications and no documented effect on viewing (<https://help.escribemeetings.com/hc/en-us/articles/43666631650957>).
  - BoardPro cannot restrict specific papers (<https://help.boardpro.io/en/articles/12345350-can-i-restrict-document-access-for-specific-users>).
- **Conflicts:**
  - Basel Committee, *Corporate governance principles for banks* (2015) ¶83: a member must "promptly disclose" a conflict and "abstain from voting" (<https://www.bis.org/publications/201507-guidelines-corporate-governance-principles-banks.pdf>).
  - ORIC: a conflicted director should "Ask not to be given any board or internal papers that discuss the matter", "declare your interest and leave the room", and the board decides the extent of the restriction (<https://www.oric.gov.au/node/8287>).
  - DLA Piper: conduct provisions may include "exclusion from board meetings and receipt of board papers" (<https://www.dlapiper.com/insights/publications/2024/08/the-good-the-bad-and-the-uncooperative-director> **[snippet]**).
  - **No source says whether the agenda line itself should be hidden from the conflicted director.** Admincontrol keeps the title, Diligent Community makes it a setting.

**Taken from this:**
- **Membership is the default audience.** Everyone in the committee's audience reads every item and paper unless the item or paper narrows it. Nobody sets permissions per meeting (E01 research, OnBoard reviews).
- **Restrictions narrow, never widen:** "Only these people" on an item or a paper. A restriction lives on the item or paper, not on the file version, so replacing a paper keeps it, unlike Diligent.
- **Members-only sections** (in camera, without management or guests), like Diligent Community and eScribe's closed sessions.
- **Item attendees:** a presenter or invitee for one item reads that item only (Bank of Cyprus §5.7).
- **A restricted paper is not listed at all** for those outside it. A restricted item shows its number and title to the rest of the audience, so they know to leave the room (Admincontrol), unless the item is marked "hide the title", when they see "Item 7 — restricted" (Diligent Community's setting).
- **Recusal (E04) narrows the same rule from the other side.** The rule has a named place for the exclusion of conflicted members (ORIC, DLA Piper), and E04 fills it in.

### 3.7 Scheduling, mode and time zones
- **Products:**
  - BoardEffect events: In-person, Virtual or Hybrid (<https://help.diligentoneplatform.com/helpdocs/d1p/en-us/Content/boardeffect/admin/workroom/workroom_events/create_manage_boardeffect_event.htm>).
  - OnBoard keeps the remote link apart from the location (<https://help.passageways.com/hc/en-us/articles/360037898832>) and shows meetings in the device's time zone (<https://help.passageways.com/hc/en-us/articles/47179919737485>).
  - Boardable and Diligent set a time zone per meeting (<https://docs.boardable.com/knowledge/create-single-date-meeting>, <https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/books/add-agenda-with-agenda-builder-bwa.htm>).
  - OnBoard has no cancel status (delete or archive). No vendor publishes a meeting status model.
  - iBabs reviewers ask for "a multi-day agenda… instead of having to create a new agenda for each day" (<https://capterra.com/p/180383/iBabs/reviews/> **[snippet]**).
- **Time zones:**
  - IANA: names are AREA/LOCATION, abbreviations are ambiguous ("CST means one thing in China and something else in North America"), and "Inexperienced users are not expected to select these names unaided" (<https://data.iana.org/time-zones/theory.html>).
  - Google Calendar shows an event "in the local time zones for anyone you invite" (<https://support.google.com/calendar/answer/37064>).
- **Bank charters allow video attendance:** Emirates NBD §4.1.1, §4.1.5; E01 cites Exim.

**Taken from this:**
- A meeting has a mode (in person, online, hybrid), a location, a join link as a plain `https` field, joining instructions, and an IANA zone chosen from a list of cities with their offsets (default: the tenant's zone).
- Times are shown in the meeting's zone, with the viewer's own time beside them when the zones differ ("10:00 Dhaka time · 09:00 your time"). Abbreviations are never shown alone.
- A meeting can run over several days (one start and one end). Sections can carry day headings.
- **Statuses:** draft → scheduled → in progress → held, or cancelled with a reason, with E06's minutes approved and closed after held. Cancelling keeps the record.

### 3.8 Drag and drop and accessibility
- **WCAG 2.2 SC 2.5.7 Dragging Movements (AA):** functionality that uses dragging must be possible with a single pointer without dragging. For sortable lists, provide "adjacent controls for moving the element up or down in the list" (<https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html>).
- **SC 2.5.8 Target Size (Minimum, AA):** 24 by 24 CSS pixels; SC 2.5.5 (AAA): 44 by 44 (<https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html>).
- **Older users:** they may have "reduced dexterity and fine motor control, making it difficult to use a mouse and click small targets" and "reduced short-term memory" (W3C WAI, <https://www.w3.org/WAI/older-users/>). They "make more mistakes than younger users do" (NN/g, <https://www.nngroup.com/articles/usability-for-senior-citizens/>).
- **Products:** Diligent documents a keyboard "Move to" from the tab menu (<https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-web-admin/books/editing-and-reordering-tabs-bwa.htm>). OnBoard moves sections with Up and Down buttons. BoardPro has "Move up/down" in the ⋯ menu. eScribe and Admincontrol document drag only. No vendor publishes a VPAT for its agenda builder; Boardable claims WCAG 2.1 AA for the product (<https://boardable.com/features/accessibility/>).

**Taken from this:**
- **Every move is a command first and a drag second:** a Move menu on each row (Move up, Move down, Move to another section, Make a sub-item, Make an item again, Move to position…), reachable by keyboard and by one click.
- Dragging uses a visible handle and the dnd-kit keyboard sensor, with translated announcements ("Item 4, Credit limit review, moved to position 2 in For decision").
- Targets are at least 24 px in the builder and **at least 44 px on the members' meeting page**, which never needs dragging at all.

### 3.9 What users criticise, and what to do better
| Complaint | Source | What we do |
|---|---|---|
| Permissions redone after a new version is uploaded (Diligent) | Capterra **[snippet]** | Restrictions sit on the item or paper and survive replacement |
| Agenda building "clunky" and "cumbersome" (Diligent, BoardEffect) | Capterra, G2 **[snippet]** | One screen: the tray of ready submissions on the side, standing items from the template, inline edits, no "build book" step |
| "having to build a meeting book to get a preview" (BoardEffect) | <https://www.softwareadvice.com/portal/boardeffect-profile/> **[snippet]** | "Preview as a member" shows the draft exactly as a chosen member would see it |
| No time per item (BoardEffect) | Capterra **[snippet]** | Durations, computed start times, totals and an overrun warning |
| Templates hard to make and inconsistent (eScribe) | <https://www.softwareadvice.com.au/software/352036/escribe> **[snippet]** | One template per committee type, reviewed, plus "start from an earlier meeting" |
| Multi-day meetings need separate agendas (iBabs) | Capterra **[snippet]** | One meeting can span days |
| Attachments one item at a time (iBabs) | Capterra **[snippet]** | Papers of a submission come with it in one action; secretary papers can be uploaded several at once |
| Annotations lost on replacement (Diligent, OnBoard, Admincontrol) | above | Not in E03 (annotations are Phase 5); old versions stay readable per revision, which Phase 5 needs to move notes |
| A late change means recompiling a 750-page book (Boardvantage) | above | No compiled book in Phase 4. Each paper is its own PDF, so a revision changes only what changed |

## 4. Paper access: options (for ADR-0061)
1. **Members become memo readers** (more `FORUM` participant rows, as E02 does for officers). This reuses ADR-0050, but a member would then read the whole memo: the note sheet, every version and all attachments, not just the papers placed before the committee. Recusal would have to be added to the memo rule, and the secretary could not leave an annex out of the pack. Paper access would also end when the submission closes, not when the meeting is done.
2. **Copy each paper into a meeting-owned file at publication** (`Files.copyFrom`). Access is clean, but every paper is stored, scanned and rendered twice, publication becomes a slow background job, and a copy can drift from its source.
3. **A meeting paper is a pinned file version, served through the meeting module after one SQL rule** (chosen). The meeting module decides who reads; the files module streams the pinned version or its PDF rendition for that caller and audits the open. The memo's own read rule is untouched: committee members do not become memo readers. Secretary papers use a new `meeting.paper` purpose whose `mayRead` is the same rule.

Option 3 is least privilege (members see the pack, not the file), keeps one rule for E04 to narrow, and freezes what each revision carried without copying. It changes one detail of the overview: the overview said members read the memo through ADR-0050's reader table. ADR-0061 records the change, and it is reported to the owner.

## 5. Module placement
- **Options:** meetings and agendas in the `forum` module (submissions are there, no new module), or in the `meeting` module (`AGENTS.md §4`, the existing stub).
- **Chosen: `meeting`.** The forum module already holds types, forums, members and submissions (about 30 source files). Attendance and quorum (E05) and the minutes (E06, its own module) build on meetings, not on forums. The dependency runs one way, meeting → forum, through published APIs:
  - the forum module publishes its three events, a `ForumPolicies` reader for the type version a meeting pins, and `SubmissionPlacements` to record where a submission was placed;
  - forum never refers to meetings, so `ApplicationModules.verify()` stays acyclic.
- **The forum module keeps what is forum policy:** the agenda template and agenda settings on the type version (`forum/V5`). The meeting tables go in `meeting/V1`–`V3`.

## 6. Conclusions for the design
1. **Meeting:** belongs to a forum, pins the forum type version current when it is created, and has a kind (ordinary or special), a mode, a location, a link, an IANA zone, start and end (possibly on different days), a classification, guests, and a status (draft, scheduled, in progress, held, cancelled; minutes approved and closed come in E06). The number ("BRC 3/2026", per forum and calendar year) is taken when the agenda is first published, so drafts and cancelled meetings never use one.
2. **Agenda:** one per meeting. A working draft of sections (standard, consent, members only), items (kinds SUBMISSION, STANDING with a standing kind, ITEM, BREAK) and one level of sub-items. Items carry a purpose, the resolution sought, a duration, presenters, attendees, restrictions, a "hide title" switch and papers. Publishing makes an immutable revision with numbers, change flags and pinned paper versions (ADR-0060).
3. **From submissions to items:** the builder's tray lists the forum's ACCEPTED and not yet placed submissions, oldest first and due ones first. Placing one (by drag, "Add to agenda" or from the queue's Accept) creates a SUBMISSION item with the cover-sheet data and the memo's document and attachments as papers. The late mode applies, and the forum records the placement. `ForumSubmissionChanged` keeps the item in step when the submission is returned, declined, referred, deferred or withdrawn.
4. **Papers and access:** one rule (ADR-0061): officers, or audience × published × restriction × members-only × clearance, minus the E04 exclusion hook. It is used by the meeting page, paper opens, the members' view of changes, notifications and the `meeting.paper` purpose. Members get the PDF rendition. Opens are audited per document.
5. **Consent:** a consent section; "Ask to discuss" by any member until the meeting starts lifts the item at once.
6. **Late papers and chair agreement:** the E02 late modes act at placement and publication. The chair agrees the first publication when the type says so (default on); the secretary may record the chair's agreement on their behalf.
7. **Cloning and templates:** from the type's template, or from an earlier meeting of a forum the secretary serves, without papers or submissions.
8. **Builder accessibility:** the Move menu for every row, then drag with dnd-kit 6.3.1 / sortable 10.0.0 and its keyboard sensor.
