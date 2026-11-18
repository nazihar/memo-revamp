# E04 Conflicts of interest and recusal — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **Declarations:**<br>• `meeting/V5__declarations.sql` (V5, not V4: E03 T4, built in parallel, takes `meeting/V4`) with the guard trigger;<br>• declarations for oneself and on behalf, intent changes, rulings (chair, vice-chair, on the chair's behalf), withdrawals, the windows by meeting status and the HELD corrections;<br>• the review confirmation and items to review;<br>• `GET …/declarations/mine` and the agenda's `myDeclaration` and `declarations` fields;<br>• the five notification kinds (en/ar mail texts, no parameters in subjects);<br>• audit with hashes; the matrix `p4-e04-declarations.yaml`; cross-tenant tests | L | E03 T3 merged (items and published revisions) |
| T2 | **The recusal rule on every E03 surface:**<br>• `DeclarationExclusions` in `PaperAccess` (draft and revision tables), the `seeTitle` officer clause, the recused line and what it withholds;<br>• paper content and the `meeting.paper` purpose; "What changed", the revision list and `/me/meetings` counts; consent requests;<br>• the preview with the actor's own recusal; the builder's recused rows and 403 `RECUSED`;<br>• routing of late-paper, discussion-request and item-withdrawn notifications;<br>• `Recusals` and `DeclarationChanged` for E05–E08;<br>• the property test (recusal never widens) | L | T1, E03 T4 merged (`PaperAccess`, the registry) |
| T3 | **Carry-over and the memo:**<br>• `MemoInterestLinks` in the memo module (`of`, `recordFromMeeting`, `readersAmong`);<br>• carry-over from memo interests at placement and at each publication, and from an earlier placement of the same submission;<br>• the memo interest recorded from a declaration on a submission item | M | T1, E03 T2 merged |
| T4 | **Registers and reminders:**<br>• the meeting register (declarations with natures, review status, quorum warnings with `Quorum.evaluate`, "also reads the memo");<br>• the cross-forum register with keyset paging and the XLSX export (rate-limited, audited);<br>• the sweep's declarations-due reminder and "Remind them now";<br>• the volume generator's declarations, reviews and events; `EXPLAIN` and the performance ITs | M | T2, T3, E03 T5 merged |
| T5 | **The recusal leak suite:**<br>• the recused-member and awaiting-ruling columns on every surface in `PaperSurfaceRegistry`, plus the E04 surfaces (declarations API, registers, export, audit export, memo search);<br>• canary strings in the item, its papers and their extracted text, checked in every response, email, in-app notification and export;<br>• the differential probe (recused view equals the view of the item without papers);<br>• placeholders for E05–E08 surfaces and the meta-test over `meeting`, `minutes` and `actions` controllers;<br>• the recused column added to `p4-e03-meetings.yaml` | M | T2, T3, T4 |
| T6 | **Web, declaring** (flow S; delegated, no owner review):<br>• "Declare an interest" on the meeting page, the declaration sheet, the review checklist and confirmation banner;<br>• the recused and awaiting-ruling lines; the Declarations standing item; my declarations;<br>• en/ar; 44 px targets | M | T1, T2, E03 T8 merged |
| T7 | **Web, managing declarations** (flow T; delegated):<br>• the Declarations panel on the meeting (secretary and chair), record on behalf, the chair's ruling, withdraw with a reason;<br>• quorum warnings and memo flags; the recused officer's rows in the builder and the preview banner;<br>• en/ar | M | T1–T4, E03 T7 merged |
| T8 | **Web, the register** (flow U; delegated): filters, keyset paging, the export button; en/ar | S | T4 |
| T9 | **Close:**<br>• demo seed: at the BRC meeting, a director recused from the Delta Textiles item, a member awaiting the chair's ruling on another, a declaration recorded by the secretary "by email"; at the MCC, an approver's memo interest carried to the item;<br>• journey `declarations.spec`;<br>• the manual keyboard and screen-reader pass over flows S and T;<br>• runbook: a "Conflicts of interest" section in `meetings-and-agendas.md`; ASVS rows; log and STATUS | M | all |

**Order:**
- T1 → T2 → T3 → T4 → T5 for the backend. T3 can run beside T2 once T1 is merged.
- T6–T8 follow their backend tasks. **The owner delegated these screens** (Phase 4 Q1: only E01, E02/E03 and E06 screens go to the owner), so web tasks do not wait for a review.
- T9 last.
- Each task is its own PR, merged when CI is green (standing go-ahead, 2026-11-17).

**Dependencies on E03:** T1 needs E03 T3 (published revisions); T2 needs E03 T4 (`PaperAccess`, `PaperExclusions`, the surface registry); T3 needs E03 T2 (placement); T4 needs E03 T5 (the sweep and the volume set). E03 T3 must keep `parent_item_id` on `agenda_revision_item` for the rule (design §1.6); this is noted on the E03 T3 branch when E04 T1 starts.

**In parallel:** E05's invitations, RSVP and attendance can run beside E04 (overview §2); only E05's per-item quorum waits for T2's `Recusals`.

## Acceptance criteria (selection; the full list is in `test-plan.md`)
- **T1 (declare):**
  - Given BRC 3/2026 with revision 1 published and `director@` a member, when they declare on item 5 "My brother-in-law is a director of Delta Textiles" with "I will leave the room", the declaration is RECUSED, the secretaries and the chair are told in the app, and the email body names neither the item nor the nature.
  - A second declaration by `director@` on item 5 answers 409 `ONE_OPEN`.
  - An item added to the draft but not yet published answers 404 to `director@`, while `secretary@` can record a declaration on it on their behalf.
- **T1 (ruling):**
  - Given a declaration with "I ask the chair to decide", it is AWAITING_RULING and the BRC chair is told.
  - When the chair rules "may take part", it becomes TAKING_PART.
  - When the chair is the declarant, the vice-chair rules; the chair's own ruling answers 409 `SELF_RULING`.
  - A secretary records the chair's ruling "by phone", stored with `onBehalfBy`.
- **T1 (windows):** after the meeting is HELD, `director@` cannot withdraw (409 `FROZEN`); `secretary@` can record a declaration made in the room as a correction with a reason; after MINUTES_APPROVED nobody can.
- **T1 (review):** given revision 2 adds two items, a member who confirmed revision 1 sees exactly those two items to review; confirming revision 1 again answers 409 `STALE_REVISION`.
- **T2 (rule):**
  - Given `director@` is RECUSED from item 5, the agenda shows "5 Term loan, Delta Textiles — You declared an interest" with its time, and nothing else of the item; its papers answer 404, including through `/files/{id}/…` and through `?revision=1`.
  - The same holds while the declaration is AWAITING_RULING, and stops once the chair rules "may take part".
  - With "hide the title", the line reads "5 Restricted item — You declared an interest".
- **T2 (officers):**
  - Given `secretary@` is recused from item 5, editing it or its papers answers 403 `RECUSED`; "Preview as Demo Director" shows item 5 as a recused line.
  - A late paper on item 5 goes to the vice-chair when the chair is recused.
- **T2 (notifications):** replacing item 5's paper and publishing revision 2 sends `director@` no change notice when nothing else they see changed, and `/me/meetings` shows no change for them.
- **T3:**
  - Given an MCC submission whose memo has an interest for `head.credit@` (an approver who stood aside), when it is placed, `head.credit@` holds a RECUSED declaration "Interest declared on memo CR/…" and is told.
  - Given a BRC member declares on a submission item, the memo gains their interest, and the workflow does not offer them a later step of that memo.
  - Given a deferred submission placed on the next meeting, earlier RECUSED declarations arrive RECUSED, and a TAKING_PART one arrives AWAITING_RULING.
- **T4:**
  - Given 4 of 7 BRC voting members recused from item 5 and the rule "a majority of voting members" with `recusedCount` off, the register warns that item 5 cannot be quorate even with full attendance; with `recusedCount` on it does not.
  - The cross-forum register and its export meet their budgets on the volume set, and the export is audited with its filter and count.
- **T5:** for every registered surface, the recused-member probe finds no canary string and no count difference beyond the recused line; a new controller method without a probe fails the build.
- **T6:** with the keyboard only, a director declares an interest on an item, sees the recused line, and confirms the review. All targets are at least 44 px; axe finds no serious issue in English and Arabic.
- **T7:** with the keyboard only, the secretary records a declaration "by email" for a member and records the chair's ruling.
