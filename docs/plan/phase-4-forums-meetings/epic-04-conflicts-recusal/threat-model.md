# E04 Conflicts of interest and recusal — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows; ASVS 5.0 IDs as in `docs/security/asvs-checklist.md` (chapter-level where the checklist groups them). Builds on the E03 threat model; its I1 and E2 are the risks this epic closes.

## Data flows
1. A member declares an interest in an item of a published agenda, or changes their intent, or withdraws it.
2. A secretary or the chair records a declaration on a member's behalf (in the app, from an email or phone call, or at the meeting).
3. The chair, or a secretary on the chair's behalf, rules on a declaration.
4. Placing a submission and publishing a revision carry interests from the memo and from earlier placements into declarations; a declaration on a submission item records a memo interest.
5. Every read path of the meeting module (agenda, papers, changes, preview, builder, notifications, counts) applies the recusal rule through `PaperAccess`.
6. Members confirm their review; the sweep reminds those who have not.
7. Officers read the meeting register; oversight reads the cross-forum register and exports it.
8. Later modules read `Recusals` and listen to `DeclarationChanged` (quorum, decisions, minutes, search rows).

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Someone records or withdraws a declaration in another member's name, or a member rules on their own interest | 1, 2, 3 | Self-declarations only for the signed-in user; on-behalf recording only by officers not recused from the item, stored with `recordedBy`, `how` and the declarant told at once; rulings only by the chair or vice-chair in office, never on one's own declaration (`SELF_RULING`); a secretary's ruling is recorded as on the chair's behalf. Matrix tests | V8 |
| S2 | A former member or an expired guest declares or reviews to probe items | 1, 6 | Declaring needs `seeTitle` on the latest revision (E03 rule with `until` and guest end dates); 404 otherwise | V8 |
| T1 | A declaration's nature or history is changed after the fact (to suggest the member never declared, or declared something else) | 1–3 | Nature, declarant and item immutable (trigger); transitions append to `meeting_declaration_event`; no UPDATE on events, no DELETE anywhere; audit with `natureSha256` and `reasonSha256` in the hash chain | V16, V2 |
| T2 | Two officers rule or withdraw at once and one change is lost, or a double click creates two declarations | 1–3 | If-Match on `version`, guarded updates on `(id, state, version)`, `ONE_OPEN` unique index, idempotency keys | V2 |
| T3 | A member withdraws a declaration to regain papers just before the decision | 1 | Withdrawal needs a reason, is audited, is told to the officers at once, and is minuted (E06); frozen after the item is decided (E06) and after the minutes are approved | V2 business logic, V16 |
| R1 | A member denies having declared, or claims to have declared when they did not; a chair denies a ruling | 1–3 | Each event records actor, `onBehalfBy`, how and time; the member's confirmations are recorded per revision; the register shows the full history | V16 |
| I1 | A recused member reads the item's papers, details or changes through any surface (agenda, revision list, "What changed", paper content, the generic files endpoint, preview, builder, consent requests, `/me/meetings` counts) | 5 | One anti-join in `PaperAccess` used by every surface (ADR-0062); the surface registry with a recused-member probe and canary strings per surface; a meta-test that fails for an unregistered controller method | V8, V14 |
| I2 | A recused secretary previews the agenda as another member and so sees the item | 5 | The preview applies the actor's own recusal on top of the target's view | V8 |
| I3 | Notifications or email reveal the item to a recused member or officer (late-paper requests, discussion requests, item withdrawn, agenda changes) | 5 | Per-recipient content through `PaperAccess`; officer notifications routed through `Recusals`; email bodies never hold item titles or natures; subjects take no parameters (ADR-0030) | V14 |
| I4 | The nature of a declaration ("my brother-in-law owns Delta Textiles") reaches other members, emails, logs or the audit export | 1, 2, 7 | Nature shown only to the declarant, officers not recused from the item, and oversight where the title is visible; never in email, notifications, logs or audit (hash only); the carried memo text is a fixed sentence, never the memo's reason | V14, V16 |
| I5 | A recused member learns of a hidden-title item through the Declarations standing item, a count or the register | 5, 7 | Others' declarations shown only on items the viewer sees; counts computed after the rule; the register filters items the viewer is recused from | V8, V14 |
| I6 | A recused member reads the matter through the memo itself (as an approver or copy-to) | 4 | Out of scope by design (ADR-0062): memo access stays with the memo rule. The register flags it to the secretary; the declaration becomes a memo interest so the member cannot approve later steps (SOD-4) | V8 |
| I7 | The register export leaks natures to someone without rights, or is pulled in bulk | 7 | Same row filter as the screen; rate-limited; audited with the filter and count; at most 20,000 rows | V8, V14, V16 |
| D1 | A flood of declarations or reviews slows the meeting page or spams officers | 1, 6 | `ONE_OPEN` per person and item; the rate limit on meeting writes (E03); officer notifications only for new declarations and pending rulings | V2 |
| D2 | Too many members recuse, so the item cannot be decided | 1, 2 | Not a security bypass, but a governance risk: the quorum warning (design §1.8) tells the secretary before the meeting | V2 business logic |
| E1 | A recused officer changes, removes or moves the item they are conflicted on, or decides its late paper | 5 | Builder and late-paper guards answer 403 `RECUSED`; requests routed to the vice-chair or the other secretaries | V8 |
| E2 | A pending ruling is treated as "not recused", so papers flow while the chair has not answered | 1, 3 | AWAITING_RULING is effective in the rule (fail-closed); property test; the matrix has a column for it | V8 |
| E3 | A crafted `itemId` from another meeting or tenant attaches a declaration where the actor has no access | 1, 2 | The item must belong to the meeting in the path (composite foreign key and a check); RLS; cross-tenant tests | V8 |

## Residual risks
- **The memo route (I6):** a conflicted member who reads the memo through the memo rule can still read it. This is visible to the secretary and is handled through the memo's access, not hidden by the meeting.
- **People in the room:** recusal hides papers and records the departure; it cannot stop a member hearing a discussion they should leave. The minutes record that they took no part (E06).
- **Papers already read:** a member who read the papers before declaring has read them. The paper-open audit shows it, and the register shows when the declaration came.
- **Oversight natures on hidden-title items:** `forum.read` sees a nature only where it sees the title, so an auditor may need the secretary for a hidden item. E08 revisits with the decision register.
- **No standing register:** a member who forgets to declare is not flagged automatically (Phase 4 Q6). The review confirmation records that they were asked.

## Reviewed at phase close
Reviewed at phase close on 2026-11-28 (Phase 4 E08 T11): **every mitigation holds as built**. Three leaks were found by the suites and fixed during the phase.
- **Found and fixed:**
  - I1, I5 (E04 T5): a recused officer read the hidden title of their own item in `GET …/declarations/mine`, in the review's `toReview` and in the answers to declaring, intent changes, rulings and withdrawals. Fixed with `DeclarationAccess.officerReadsTitle`, and `RecusalLeakIT` probes it.
  - E1 (#204): when the chair is recused, the vice-chair decides the item's late papers (`LateRequestsIT`).
  - I1 (E06 T3): a recused alternate not named on the member's restricted item got the recused view of its decision. The recused view now needs the item to be unrestricted or restricted to the viewer.
- **Changed by the build:**
  - The one rule (`DeclarationExclusions` inside `PaperAccess`, and `RecusalStore`) now also covers E05's invitations and quorum, E06's decisions, minutes copies and extracts, E07's actions, tracker and action taken report, and E08's register and analytics.
  - An alternate inherits the member's recusals (ADR-0064, `AlternateRecusalIT`).
  - The register computes recused items in the request from committed declarations (E08 I2), so a declaration applies at once (`DecisionRegisterIT`).
- **Evidence:**
  - S1, S2, E3: `DeclarationsIT`, `DeclarationReviewIT`, `MeetingCrossTenantIT`, matrix `p4-e04-declarations.yaml`.
  - T1, T3: the guard trigger and `FROZEN` (`DeclarationStateTest`, `MeetingCrossTenantIT`). T2: `DeclarationConcurrencyIT`. R1: `meeting.declaration.*` (`AuditActionCoverageTest`).
  - I1, I2, I5, E1, E2: `RecusalRuleIT`, `RecusedOfficerIT`, `RecusalNarrowsProperties`, `RecusalLeakIT`, `PaperLeakIT`. I3, I4: `DeclarationNotificationsIT`, `RecusalMailIT`, `MailTemplatesTest`, the AUDIT channel. I6: `MemoInterestFromMeetingIT` (SOD-4). I7: `DeclarationRegisterIT`, `DECLARATION_EXPORT_PER_USER`.
  - D1: the caps and `ONE_OPEN`. D2: the register's quorum warning (`DeclarationRegisterIT`).
- **Residual risks:** all five confirmed.
  - On oversight natures for hidden-title items, which was to be revisited with the register: the register holds no natures (`DecisionRowTest`) and shows a hidden-title item as a line. An auditor still asks the secretary for such an item.
- **Pen test:** area 1 of `docs/security/pen-test-scope.md`. Its accounts include the demo director, recused and awaiting a ruling.
