# E04 Conflicts of interest and recusal — log

## 2026-11-18
- **Plan written under the delegated gate** (Phase 4 Q1; E04's screens are not among those the owner reviews):
  - research: R1 (nothing new to add) and R6 from the Basel Committee's 2015 principles, EBA/GL/2021/05, the Companies Act 2006 s.177 and s.185, the UK model articles, the UK Code 2024, the Charity Commission, ORIC, the ABA (2025), CGI's *Minute taking* guidance and factsheet, DWPV on directors' information rights, a Bangladeshi bank's code of conduct and Bangladesh Bank's 2025 dissent directive, and the help centres or product pages of eScribe, OnBoard, Diligent, Boardvantage, Convene, Admincontrol, BoardPro, iBabs and BoardEffect;
  - ADR-0062 (per-item declarations and one recusal rule);
  - design, threat model, tasks T1–T9, test plan with the recusal leak suite, and flows S–U recorded as delegated.
- **Choices that came from the research:**
  - **Leave the room by default.** Basel ¶83 and EBA ¶115(c) ask for abstention; EBA ¶125, ORIC, the ABA and the Charity Commission go further (no papers, no discussion, no decision). A bank product takes the stricter reading.
  - **A ruling by the chair** for a member who thinks the interest is immaterial (EBA ¶119, CGI §5.3.6, ORIC), with the papers withheld until then (fail-closed).
  - **The declaration is the access change.** Every portal studied treats a declaration as a record and restricts access by hand; none documents a declaration that withholds papers, notifications and the decision.
  - **Carry-over** from the memo's interests (SOD-4) and from an earlier placement of the same paper, and a declaration on a submission item recorded back on the memo, so a director never declares the same matter twice and never approves it later.
  - **One review confirmation per meeting**, re-asked only for new items (ABA's disclosure statement; CGI warns against boilerplate).
  - **The quorum warning** before the meeting from the committee's own rule (CGI §5.2.1: the secretary must know whether the meeting is quorate; recusals are the usual reason for doubt).
- **Change from the overview, recorded in ADR-0062, to report to the owner:** the recused member's minutes show the item's heading, their declaration and the decision, not the discussion. The overview said "a version without the items they were recused from". CGI §6.4 found financial-services boards lean to redaction, with a recognised middle view that the director "is entitled to know the decision, but not any detail of the discussion". E06 builds it.
- **Confirmed against the research:** Phase 4 Q6 (per item now, a standing register later) is consistent with every source; per-matter disclosure is the core duty, and a register is good practice on top (EBA ¶114, s.185, CGI).
- **Found while reading:**
  - Memo interests (`memo_interest`) are internal to the memo module; T3 adds a published `MemoInterestLinks`.
  - The rule needs each revision item's parent (sub-items inherit the parent's recusal). E03 T3 must keep `parent_item_id` on `agenda_revision_item`; to note on the T3 branch.
  - E03 T2 (`meeting/V2`) and T3 (`meeting/V3`) are in progress, so E04's migration is `meeting/V4`.
- **Uncertain, no owner input needed:** whether a recused member's alternate stands in for that item is left to E05 (no source found); "stays but does not vote" is not offered in Phase 4 and is revisited with electronic voting in Phase 5.

## 2026-11-18 — T1 declarations (backend)
- **The migration is `meeting/V5`, not V4:** E03 T4 (members' reads, consent requests) is built at the same time and takes `meeting/V4`. E03 T4 merged first (#180), and this branch merged master with V4 before merging itself. Recorded in tasks.md and design §7.
- **Built:**
  - `meeting/V5__declarations.sql`: `meeting_declaration`, `meeting_declaration_event`, `meeting_declaration_review`, `meeting_declaration_reminder` (RLS forced, `tenant_id` first, composite foreign keys, the `ONE_OPEN` partial unique index, the effective-declaration index for T2's anti-join, the register indexes); no DELETE or TRUNCATE for the app role, no UPDATE on the history, the reviews and the reminders; on declarations only `state`, `intent`, `ruling` and `version`, behind the guard trigger (other columns, a version that does not move by one, and pairs of states outside design §1.2 are refused). `meeting_item` gains the unique `(tenant_id, meeting_id, id)` for the declaration's foreign key. jOOQ, the contract and the web client regenerated.
  - Declaring for oneself (LEAVE → RECUSED, ASK_CHAIR → AWAITING_RULING) on an item whose title the actor sees in the latest revision (officers: any live draft item too); recording for someone in the audience or an officer, by an officer not recused from the item, with `how` and an optional note; `ONE_OPEN` (409 with the open declaration's id); intent changes by the declarant; rulings by the chair, by the vice-chair when the chair declared, is recused or is absent, and recorded by a secretary on the chair's behalf (`SELF_RULING`, `NO_CHAIR`, 403 `RECUSED`); withdrawals with a reason by the declarant or an officer not recused from the item; the windows by meeting status and the secretary's corrections once HELD (`FROZEN` otherwise). Guarded updates with If-Match (412), an event row per transition, Idempotency-Key on the create.
  - The review: `GET …/declarations/mine` (the actor's declarations, the latest revision, the last revision confirmed and the items to review) and `POST …/declarations/review` against the latest revision (`STALE_REVISION`), once per person and revision.
  - The draft agenda's and the members' agenda's items gain `myDeclaration` and `declarations` (names and states of the others' open declarations). After E03 T4 (#180) merged, `SEES_ITEM` and the items to review use `PaperAccess.revision` (the lines not HIDDEN) instead of a copy of the title rule.
  - Notifications: `MEETING_DECLARATION_RECORDED`, `MEETING_DECLARATION_MADE`, `MEETING_RULING_NEEDED`, `MEETING_DECLARATION_RULED` and `MEETING_DECLARATIONS_DUE` (sent from T4), English and Arabic mail texts with subjects without parameters and bodies without the item's title or the nature, web labels and in-app texts with the item's number; the prompt to declare interests in E03's agenda-published and agenda-changed mail bodies.
  - Audit: `meeting.declaration.made`, `.intent_changed`, `.ruled`, `.withdrawn` and `meeting.declarations.reviewed`, with ids and the SHA-256 of the nature, reasons and ruling notes, never the text.
  - Tests: `DeclarationStateTest`, `DeclarationFieldsTest` (unit), `DeclarationsIT`, `DeclarationReviewIT`, `DeclarationNotificationsIT`, `DeclarationConcurrencyIT`, declarations and the guard trigger in `MeetingCrossTenantIT`, `notification-messages.test.ts` (web); matrix `p4-e04-declarations.yaml`.
- **Deviations from the design** (recorded in `design.md` as "as built"): the intent column is updatable with the state; `correction` on declarations and events; one review row per person and revision; withdrawals told with the `MEETING_DECLARATION_MADE` and `MEETING_DECLARATION_RECORDED` kinds and `change: WITHDRAWN` (no sixth kind); a ruling that would not change the state is `WRONG_STATE`; intent changes, rulings and reviews are frozen once HELD (the correction covers recording and withdrawing only); standing items and breaks are not in the items to review, and a break takes no declaration; on the members' agenda the others' declarations show only on items the viewer sees in full.
- **Left for T2:** `DeclarationExclusions` in `PaperAccess`, the recused line and `recused`, the builder's 403 `RECUSED`, `Recusals` and `DeclarationChanged`, routing of officer notifications by recusal beyond declarations (late papers, discussion requests, items withdrawn).

## 2026-11-19 — T2 the recusal rule on every E03 surface (backend, and the member's recused line on the web)
- **No migration:** the T1 tables and the effective-declaration index serve the rule.
- **Built:**
  - `DeclarationExclusions` in `PaperAccess` (draft and revision tables, officers included); `NoExclusions` kept for tests only; the officer line clause in `lineCondition`; `Visibility.recused`.
  - The recused line and `recused` on the members' agenda (every revision, "What changed", the revision list, `/me/meetings` counts), the draft and the preview (the target's recusals and the previewing officer's own); paper content and `meeting.paper` reads 404/403 through the rule; consent requests 404 for a recused member.
  - The builder's 403 `RECUSED` for every change of an item (or its papers) the actor is recused from, and for a new version of its paper file; the publish preview cut to the line for a recused officer.
  - Late requests routed to whoever decides for the item (chair, vice-chair, else the secretaries not recused), and only the decider answers in the app; discussion requests and item-withdrawn notices not sent to recused officers.
  - `Recusals` and `DeclarationChanged` for E05–E08 (design §2 as built).
  - Web: the meeting page shows "— You declared an interest" or "— Waiting for the chair's decision, papers withheld until then" on a recused line (en/ar).
  - Tests: `RecusalRuleIT`, `RecusedOfficerIT` (builder, preview, publish preview, late-paper routing, discussion and withdrawn notices), `RecusalsApiIT`, `RecusalNarrowsProperties` (40 seeded runs over an agenda with every shape the rule distinguishes, for officers, members, guest, attendee and `forum.read`, revision and draft; declarations rolled back), the recused member (one title line, one hidden, one awaiting a ruling) on every `PaperSurfaceRegistry` surface in `PaperLeakIT`; `DeclarationsIT` follows the recused draft row; web `meeting-page.test.tsx`.
- **Deviations:** the property test is a seeded JUnit integration test, not jqwik (the rule is SQL; jqwik does not run in the Spring test context). The declaration enums moved to the published package for `Recusals`. A recused officer's draft row carries `""` as the title when it is hidden (the field stays required, so the web builder types do not change). The decision on a late paper in the app moved from "the chair in office" to "who decides for the item".
- **Left:** the recused and awaiting-ruling columns in `p4-e03-meetings.yaml` and the canary/differential leak suite (T5); the builder's and the preview's recused rows on the web (T7: today the builder shows the row's title and no papers; a hidden title shows empty); the vice-chair's "decide" button when the chair is recused (T7, `canAgree` is still the chair in office).

## 2026-11-19 — T6 declaring an interest (web, flow S)
- **No backend change.** The screens use T1's endpoints and T2's `recused` and `myDeclaration` as they are.
- **Built** (`web/src/features/meetings/declarations/`, wired into E03's meeting page):
  - **The Declarations panel** above the agenda (S1's banner): "Please read the agenda and tell us about any interest", "9 items to review" ("2 new items to review" after a confirmation) and "Review and declare"; once confirmed, "You confirmed your declarations for revision 2 of the agenda"; and the member's own open declarations, each with "My declaration". Nothing is shown when there is nothing to say.
  - **On each item** whose title the member sees (in full or title only), an item or a submission: "Declare an interest". Not on "Restricted item", breaks or standing items. Shown only on the latest revision while the meeting is SCHEDULED or IN_PROGRESS, and not to a `forum.read` holder outside the audience (the page does not read `/declarations/mine` for them).
  - **The declaration sheet** (S2): a side sheet with the interest (grows with the text; "Please say what your interest is"), leave the room (default, "(recommended)") or ask the chair, each with its consequence. It has one Idempotency-Key per request; a changed request gets a new key. Success tells the member who was told.
  - **The recused line:** T2's heading suffix stays, and S1's second line is added below it. RECUSED: "You will be asked to leave the room for this item. Its papers are not shown to you." AWAITING_RULING: "You asked the chair to decide whether you may take part." TAKING_PART shows the item in full with "You declared an interest. The chair decided you may take part." Each line has "My declaration".
  - **My declaration** (S4):
    - when and how the declaration was made: by the member in the app, recorded by someone (by email, by phone, in person or at the meeting, with their note), or carried over from the memo or an earlier meeting;
    - the interest in quotes, what the member chose, and the chair's decision with its note;
    - "Ask the chair to decide instead" or "I will leave the room instead", sent with If-Match;
    - "Withdraw this declaration…", which needs a reason and says that the secretary and the chair will be told and that it will be in the minutes;
    - once the meeting is held, it can only be read.
  - **The review** (S3): a side sheet listing `toReview` (every item before a first confirmation, then only those added since, with "Only the items added since you confirmed revision 1 are listed"). Each row has "I have an interest", which opens the declaration sheet over the list, or what was declared (it opens My declaration). The sheet ends with "I have declared my interests in this agenda" against the latest revision. On `STALE_REVISION` it says so, reads the new list and asks again.
  - **The Declarations standing item** lists the open declarations on the items the reader sees, theirs and others' ("S. Ali — will leave the room for item 5"), never the nature.
  - **Refusals in words, where they happen:**
    - in the sheet: `ONE_OPEN`, `FROZEN`, `WRONG_STATE`, `STALE_REVISION`, 403 `RECUSED`, 403, 404 and 412; after a 404, 409 or 412 the agenda and declarations are read again;
    - at the field: 422 `NATURE_REQUIRED`, `REASON_REQUIRED` and `LENGTH`.
  - **Focus:**
    - an item's declaration button keeps its place, so "Declare an interest" becomes "My declaration" (and back after a withdrawal) without losing focus;
    - a review row's button keeps its place in the same way;
    - when the opener is gone (the review button after confirming), focus goes to the panel's heading.
  - **Targets:** 44 px for all controls; `FormDialog` gained `large` for that.
  - **Strings:** en and ar in a new catalogue part `declarations` (`/meetings` routes; 2.0 KiB en, 2.5 KiB ar gzip).
  - **Tests:** `declarations.test.tsx` (8):
    - declare with leave the room: Idempotency-Key kept on retry, the body, the empty-field message, 44 px, axe;
    - ask the chair, and the awaiting line;
    - a 422 `LENGTH` at the field, then `ONE_OPEN` in words, with a new key for the changed request;
    - change intent and withdraw with If-Match and a reason, `FROZEN` in words, focus kept;
    - the review asking only about new items, declaring from the list, `STALE_REVISION`, confirming;
    - the standing item and where Declare is offered or not ("Restricted item", break, standing item);
    - nothing for a `forum.read` reader or once HELD;
    - Arabic RTL with axe.
    `meeting-page.test.tsx` and `agenda.test.tsx` were updated for the new part, and the title-only and recused lines now carry their button.
- **Deviations from flow S:**
  - "Declare an interest" is on items and submissions only, not on standing items. This matches T1's review, which does not ask about standing items or breaks; S1 drew none on items 1–2.
  - The banner is a "Declarations of interest" panel. The confirmation line gives the revision but not the date: `MyDeclarationsResponse` has no confirmation time.
  - The carried-over line does not name the memo or its date. The nature T1 stores ("Interest declared on memo {number}") is quoted just below, and the response carries no memo-interest date.
  - S4 also offers "I will leave the room instead" while waiting for a ruling or after a ruling to take part, as the lifecycle allows.
  - A declared row in the review opens My declaration instead of being plain text, so focus stays on it.
  - Dates in My declaration are on the meeting's clock, like the rest of the page.
- **Left:** officers' screens (T7); the register (T8); the journey `declarations.spec` and the manual keyboard and screen-reader pass (T9).
