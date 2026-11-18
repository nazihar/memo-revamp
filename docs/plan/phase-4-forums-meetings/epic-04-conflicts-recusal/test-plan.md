# E04 Conflicts of interest and recusal — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. Phase-4 special test (`§12`): "Recusal enforced across API, search, notifications and exports". CI stages: PR (unit, integration, matrix, leak suite, smoke E2E), nightly (PIT, browsers, visual, pseudo-locale), weekly (load).

## Scope and risks
- **Risks covered:**
  - an item's papers, text, changes or notifications reaching a recused member or officer through any surface (the main risk; overview risk R1);
  - papers flowing while a ruling is pending;
  - a declaration recorded, ruled or withdrawn by the wrong person;
  - a declaration's nature or history changed afterwards;
  - the nature reaching email, logs, the audit export or other members;
  - interests on the memo not reaching the meeting, or a meeting declaration not reaching the memo's later steps;
  - an item that cannot be quorate discovered only at the meeting.
- **Seed:**
  - the E03 seed: the BRC meeting with a published agenda (revision 2), restricted and hidden-title items, a consent section, a late paper; the MCC meeting with the credit proposal placed from the queue;
  - `director@` (BRC member), `secretary@` (BRC and MCC secretary), the BRC chair and a BRC vice-chair (T9 seed), `head.credit@` (MCC chair and an approver with a memo interest), `auditor@` (`forum.read`), `user@`, a guest and an item attendee;
  - **canaries:** a test-only item whose title, description, resolution sought, paper titles, file names and extracted text each carry a distinct canary string (`CANARY-T-…`, `CANARY-D-…`, `CANARY-P-…`, `CANARY-X-…`), and a declaration nature `CANARY-N-…`.

## Tests per slice
| Task | Layer | Tests |
|---|---|---|
| T1 | Unit | `DeclarationStateTest`: every allowed and forbidden transition of design §1.2, including from WITHDRAWN, rulings on one's own declaration, and the windows per meeting status (§1.1) |
| T1 | Unit | `DeclarationFieldsTest`: nature 1–1,000 after trimming, reason 1–500, `how` required on behalf, `howNote` 0–200 |
| T1 | Integration | `DeclarationsIT`: declare (LEAVE and ASK_CHAIR), on behalf (each `how`), change intent, rule (chair, vice-chair when the chair declared or is recused, secretary on the chair's behalf), withdraw with a reason; HELD corrections; frozen after MINUTES_APPROVED and on CANCELLED; `/declarations/mine`; the agenda's new fields; audit for each with hashes and never the text |
| T1 | Integration | `DeclarationReviewIT`: items to review per person and revision; confirm; `STALE_REVISION`; a later revision with new items asks again only about those |
| T1 | Integration | `DeclarationNotificationsIT`: each kind to the right people (officers not recused from the item; the chair or vice-chair for rulings; the declarant when recorded for them); subjects without parameters; no item title or nature in email bodies (canary check) |
| T1 | Persistence/RLS | `MeetingCrossTenantIT` gains declarations, events, reviews and reminders; the trigger refuses changes to nature, user and item and illegal transitions; no DELETE; no UPDATE on events and reviews |
| T1 | Concurrency | `DeclarationConcurrencyIT`: two parallel declarations by one person on one item → one succeeds (`ONE_OPEN`); two parallel rulings with one version → one 412; the same idempotency key twice → one declaration |
| T2 | Property | `RecusalNarrowsProperties`: for random agendas (sections, items, sub-items, restrictions, hidden titles, members-only sections), audiences and declarations, the set of items and papers each person sees with `DeclarationExclusions` is a subset of the set with `NoExclusions`, and differs exactly by the items the person is effectively recused from (or whose parent they are) |
| T2 | Integration | `RecusalRuleIT`: the rule's matrix (viewer source × declaration state × item or sub-item × hidden title × revision old or latest × officer or member) through the agenda, revision list, "What changed", paper content (PDF, original, `?revision=`), the generic files endpoint, consent requests and `/me/meetings` counts |
| T2 | Integration | `RecusedOfficerIT`: builder edits, papers, restriction, moves and removal of the item → 403 `RECUSED`; the draft shows the line only; the preview applies the actor's recusal; late-paper routing to the vice-chair and to the secretaries when nobody can decide |
| T2 | Integration | `RecusalNotificationsIT`: agenda-change notices, discussion requests and item-withdrawn notices never reach a recused person about that item |
| T2 | Integration | `RecusalsApiIT`: `Recusals` answers and `DeclarationChanged` (with `tenantId`) after each transition, once |
| T2 | Unit (PIT nightly) | `DeclarationExclusions` and the condition builder |
| T3 | Integration | `DeclarationCarryOverIT`: memo interests carried at placement and at publication (once, idempotent); the fixed nature text, never the memo's reason; earlier placements carried (RECUSED stays RECUSED, TAKING_PART arrives AWAITING_RULING); cloning copies none |
| T3 | Integration | `MemoInterestFromMeetingIT`: a declaration on a submission item records a memo interest with the fixed reason; the workflow excludes the person from a later step of that memo (SOD-4); withdrawing the declaration leaves the memo interest; a MEMO-sourced declaration does not write back |
| T4 | Integration | `DeclarationRegisterIT`: the meeting register's rows, reviews, quorum warnings (with `recusedCount` on and off, required roles, composition) and "also reads the memo" flags; a recused officer sees only their own row for the item; the cross-forum register's row filter (titles by `seeTitle`, natures only where allowed) and keyset paging |
| T4 | Integration | `DeclarationExportIT`: the XLSX has the screen's rows only, at most 20,000; rate limit; `meeting.declarations.exported` with filter and count |
| T4 | Integration | `MeetingSweepIT` (test clock) gains the reminder: once per person and revision on the working day before, from 09:00 in the meeting's zone, on a calendar with a holiday; nobody who confirmed; per tenant with the context set |
| T4 | Performance | `DeclarationVolumeIT` (weekly `load`): the budgets of design §8 on the E03 volume set plus 13,500 declarations; `EXPLAIN` for the agenda's anti-join and both register orders |
| T5 | Leak suite | `RecusalLeakIT` (below) |
| T6–T8 | Web unit | The declaration sheet (intent default, validation messages); the recused and awaiting lines; the review checklist; the panel's states; the ruling dialog; the register's filters |
| T6–T8 | E2E | `declarations.spec`: a director declares and sees the recused line; the chair is asked and rules; the secretary records a declaration "by email" and withdraws one entered in error; the director confirms the review; the register lists them and exports |
| T6–T8 | Accessibility | axe on every new screen (en, ar, pseudo-locale); the manual keyboard and screen-reader pass (NVDA and VoiceOver) over flows S and T; 44 px targets on the member's screens measured |
| T6–T8 | i18n | ICU messages for states, intents, `how`, the recused lines and the quorum warning; right-to-left layout of the sheet and the panel |

## Authorisation matrix tests
- `p4-e04-declarations.yaml`: every E04 endpoint × secretary (own forum, another forum, recused from the item), chair (and recused), vice-chair, `forums.manage`, member (seeing the item, restricted from it, recused, awaiting a ruling, taking part), former member, guest, item attendee, `auditor@` (cleared and not), `user@`.
- `p4-e03-meetings.yaml` gains the **recused** and **awaiting-ruling** columns on every item and paper endpoint, so each E03 endpoint is checked for a recused viewer.
- Derived from design §4; every ✓ and every – or ✗ cell is a test.

## State-machine tests
Every allowed and forbidden transition of the declaration (design §1.2), crossed with the meeting status windows (§1.1): DRAFT, SCHEDULED, IN_PROGRESS, HELD (corrections only), MINUTES_APPROVED and CANCELLED (frozen).

## The recusal leak suite (`RecusalLeakIT`)
- **Registry:** `PaperSurfaceRegistry` (E03) becomes the recusal registry. Each entry names the surface, how to call it for a viewer, and what an unrestricted baseline looks like.
- **Viewers per surface:** a member RECUSED from the canary item; one AWAITING_RULING; a recused secretary; a recused chair; and, as controls, the same people TAKING_PART and with no declaration.
- **Three assertions per surface and viewer:**
  1. **Canary:** no canary string (title when hidden, description, resolution sought, paper title, file name, extracted text, nature) appears in the response body, headers, any email sent or in-app notification created during the probe, or any export produced.
  2. **Differential:** the recused view equals the view of a control member for an agenda where the canary item has no papers and no text, apart from the recused line itself. This catches counts, totals, "2 changes", page counts and facets.
  3. **Status:** item-specific reads answer 404 (or 403 `RECUSED` for an officer's builder change), never 200 with partial data.
- **Surfaces (E04):**
  - the members' agenda (latest and `?revision=`), the revision list, "What changed", `/me/meetings`;
  - paper content (PDF and original) and the generic files endpoints for `meeting.paper`;
  - the preview as a member (actor recused, target not, and the reverse);
  - the builder's draft and every item change endpoint (recused officer);
  - consent discussion requests;
  - per-recipient notifications (in-app and email) for publication, revision changes, late papers, discussion requests and withdrawn items;
  - the declarations endpoints, the meeting register, the cross-forum register and its export;
  - memo search (the canary paper text is never a hit; the memo's own rule decides the memo);
  - the audit viewer and the verifiable export (no nature or title canary; hashes only).
- **Placeholders for later epics** (each fails the build when its controller exists without a probe):
  - E05: invites and their updates and cancellations (no item content), attendance, the item's quorum;
  - E06: decisions, vote counts and named dissent, draft and approved minutes (the recused version of ADR-0062), resolutions;
  - E07: actions from the item;
  - E08: the decision register, `search_decision`, analytics and their exports.
- **Meta-test:** every controller method in `meeting`, `minutes` and `actions` (and any module that later reads meeting data) is in the registry; a method that is not fails.

## Security tests
- 404 for an item or paper a recused person asks for directly, including old revisions and file ids from before the declaration.
- 403 `RECUSED` for a recused officer's item changes; `SELF_RULING` for a ruling on one's own declaration.
- Natures and reasons never in logs (log capture during `DeclarationsIT`), email, notifications or audit details.
- A declaration on an item of another meeting or tenant is refused.
- Rate limit on meeting writes covers declarations; the export rate limit.

## Performance
Design §8, on the E03 volume set plus declarations (T4, weekly `load`). The member's agenda keeps its E03 budget with the anti-join.

## Accessibility and i18n
- Flows S and T by keyboard only; screen-reader labels for states ("Declared — you will leave the room for this item").
- 44 px targets on the member's screens (E03 research §3.8); 24 px elsewhere.
- English and Arabic, with the pseudo-locale nightly.

## Exploratory charters
- As a director with two conflicts on one agenda, try to learn anything about the second item from the meeting page, the changes view, notifications and the browser's network panel.
- As a recused secretary, try to influence or read the item through the builder, the preview, publishing and the late-paper flow.
- As the chair, rule on declarations made at the meeting while the meeting is in progress.

## Acceptance mapping
| Overview requirement | Tests |
|---|---|
| Declarations per item, by the member or recorded by the secretary or chair | `DeclarationsIT`, `p4-e04-declarations.yaml` |
| Pre-meeting declaration prompt | `DeclarationReviewIT`, `MeetingSweepIT`, `DeclarationNotificationsIT` |
| Append-only; a withdrawal is a new entry | `DeclarationStateTest`, `MeetingCrossTenantIT` trigger checks |
| Interests on the memo carry over | `DeclarationCarryOverIT` |
| Recusal hides restricted papers; title stays unless the item hides it | `RecusalRuleIT`, `RecusalLeakIT` |
| Excluded from the item's quorum and decision | `RecusalsApiIT`, the quorum warning in `DeclarationRegisterIT`; E05 and E06 placeholders |
| No notifications about the item | `RecusalNotificationsIT`, `RecusalLeakIT` |
| Enforced server-side, never by the UI | `RecusalRuleIT`, `RecusedOfficerIT`, matrix columns |
| One rule across API, lists, search, notifications, exports, invites, minutes | `RecusalLeakIT` with its placeholders and meta-test |
| Register view for secretaries, chairs and auditors | `DeclarationRegisterIT`, `DeclarationExportIT` |

## Exit checklist
- [ ] All of the above green; matrix complete with the recused columns; the leak suite covers every registered surface and the placeholders are in place.
- [ ] `declarations.spec` on CI and against dev.
- [ ] Manual accessibility pass recorded.
- [ ] ASVS rows, runbook section.
- [ ] Screens recorded as delegated in `ux-review.md`.
