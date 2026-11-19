# E06 Minutes and resolutions — UX review

> `AGENTS.md §5` Step 3 and Phase 4 Q1: **minutes with resolutions go to the owner before build.** The web tasks (T10, T11) wait for the owner's answer below; the backend tasks do not. Wireframes are structural; the look is the existing Ledger direction and components. "Committee" is the default English word for a forum. Flows continue from E05's Z: **AA** recording decisions, **AB** decisions as members see them, **AC** resolutions and extracts, **AD** drafting the minutes, **AE** the chair's review, **AF** circulation, **AG** approval and confirmation, **AH** the memo side, **AI** the committee type's Minutes tab.
>
> **How to review:** read the "Recommendations" list at the end first; each has a recommended answer. Then glance at the flows. "As recommended" is a complete answer.

## Design principles taken from the research
1. **The decision is a form, the minutes are a document.** The secretary records each decision in a short form beside the agenda (outcome, text, conditions, vote, names); the minutes are a real Word document from the committee's own template, edited in the browser (research §4: in-portal editors are criticised for formatting).
2. **Outcomes in the committee's own words, as large buttons:** "Approved", "Approved with conditions", "Deferred", … (the committee type's labels).
3. **Nobody types who took no part.** It comes from the declarations and attendance; a person who declared an interest cannot be picked as voting.
4. **Say what will happen before it happens:** "The memo will move on when you mark the meeting as held"; "Resolution numbers are given when the meeting ends".
5. **One place for directors:** "Minutes to read" on the meeting page and in "My meetings"; their own copy; two buttons, "No comments" and "Suggest a correction".
6. **The copy is made for the reader.** A director who declared an interest sees the heading, their declaration and the decision for that item, never the discussion (ADR-0062), with a sentence that says so.
7. **For the director: big, plain, calm** (E03 principle 5): 44 px targets, no workflow words, one primary action.

## Flow AA — Recording decisions (secretary)

**AA1. The decision panel during the meeting** (the meeting page's officer view; the item in progress from E05 is selected; on a tablet the panel takes the right half)
```
BRC 3/2026 — in progress                               Now: item 5  Term loan, Delta Textiles [ Change ▾ ]
● Quorate for item 5: 5 of 7 voting members present, 4 needed (Demo Director declared an interest)
────────────────────────────────────────────┬──────────────────────────────────────────────────────────
 Agenda                                      │ Item 5 — Term loan, Delta Textiles      For decision
  1  Welcome and apologies        —          │ Paper CR/HO-CR/2026-27/0042 · resolution sought:
  2  Declarations of interest     —          │ "To approve a term loan of BDT 60 million …"
  3  Minutes of the last meeting  ✓ Approved │
  4  Matters arising              ✓ Noted    │ Decision
▶ 5  Term loan, Delta Textiles    ○ not yet  │ ( ) Approved   (•) Approved with conditions   ( ) Rejected
  6  Restricted item              ○ not yet  │ ( ) Deferred   ( ) Returned for more information
  Consent items                              │ ( ) Referred to another committee   ( ) Noted
  7  Compliance report Q3         ○          │
  8  Terms of reference (lifted)  ○          │ Resolution (as it will be minuted)
  9  Any other business           —          │ [ It was RESOLVED that the term loan of BDT 60 million to … ]
                                             │ Conditions                                   [ + Add a condition ]
 [ Decide the consent items together ]       │  1 [ Security perfected before disbursement                 ] ✕
                                             │ Valid until (optional)  [ 10 Mar 2027 ]
                                             │
                                             │ ▸ Record a vote (optional)
                                             │ ▸ Dissent and abstentions (optional)
                                             │ Took no part: Demo Director (declared an interest)
                                             │
                                             │ Notes for the minutes (only secretaries see these) ✓ saved
                                             │ [ CFO explained the security cover; members challenged the     ]
                                             │ [ sector outlook; Head of Credit to confirm the valuation date ]
                                             │                                   [ Record the decision ]
```
- **Record a vote** opens: For [ 4 ] Against [ 1 ] Abstaining [ 0 ] with the line "5 voting members present and able to vote". More than that is refused in words: "There are only 5 voting members present who may vote on this item."
- **Dissent and abstentions** opens a list of the people present who may vote, each with "Dissented" or "Abstained" and, for dissent, "Reason they asked to be recorded (optional)". Demo Director is not in the list ("declared an interest").
- **After recording:** the agenda line shows "✓ Approved with conditions — numbered when the meeting ends". The panel shows the decision as text with [ Correct ].
- **Not quorate:** "Item 5 is not quorate: 3 of 4 needed. You cannot record a decision." If the committee type allows it, the button "Record without a quorum…" opens E05's override dialog (reason required) and the decision is marked "To be ratified".
- **The consent items:** "Decide the consent items together" → "Items 7 will be Noted. Item 8 was lifted for discussion and is decided on its own. [ Cancel ] [ Decide together ]".

**AA2. Correcting a decision**
```
Correct the decision on item 5
  (the same form, filled with the current decision)
  Why are you correcting it? [ Condition wording agreed with the chair after the meeting ]
  ⓘ The minutes are with the chair for review. Correcting the decision sends them back to you
    to update item 5.                                   [ Cancel ]  [ Correct the decision ]
```
- **After the memo has moved on** the outcome buttons are disabled: "The memo has already moved on with 'Approved with conditions'. You can correct the wording, the conditions and the vote. To change the outcome, record it in the minutes and ask the author to resubmit the memo."
- The decision's history ("Recorded 10:42 by K. Secretary · Corrected 14 Dec by K. Secretary: Condition wording …") is under [ History ].

**AA3. Mark the meeting as held** (E03's End button gains the consequences)
```
Mark the meeting as held?
  • 6 decisions are recorded. 2 are resolutions and will be numbered in agenda order
    (item 5: BRC/2026/014, item 8: BRC/2026/015).
  • 1 memo will move on now: item 5 (Approved with conditions).
  • Item 6 has no decision. Its memo will keep waiting until you record one.
  After this, decisions you record will reach their memos at once.
                                                       [ Cancel ]  [ Mark as held ]
```
- With "when the minutes are approved" on the committee type, the second bullet reads "1 memo will move on when the minutes are approved".

**AA4. After the meeting:** the same panel, with the time the item was taken required ("When was this item taken? [ 11:05 ]", prefilled from "Now: item n" when it was used).

## Flow AB — Decisions as members see them

**AB1. The meeting page after the meeting** (E03 P1's place for E06)
```
For decision
 5  Term loan, Delta Textiles                                        For decision
     ✓ APPROVED WITH CONDITIONS — Resolution BRC/2026/014
       It was RESOLVED that the term loan of BDT 60 million to … be approved, subject to:
       1. Security perfected before disbursement.        Valid until 10 March 2027.
       Vote: for 4, against 1. S. Ali asked that his dissent be recorded.
       Took no part: Demo Director.
     [ 📄 Paper: CR/HO-CR/2026-27/0042 · 9 pages ]
 6  Restricted item
```
- **Demo Director (recused from item 5) sees:**
```
 5  Term loan, Delta Textiles
     You declared an interest in this item and took no part.
     ✓ APPROVED WITH CONDITIONS — Resolution BRC/2026/014
       It was RESOLVED that … subject to: 1. Security perfected before disbursement.
```
  No vote, names or papers. A hidden-title item shows only "6 Restricted item".
- Guests and presenters see the decisions of items they see until the minutes are approved.

## Flow AC — Resolutions and extracts

**AC1. The committee's resolutions** (committee page → Resolutions tab; secretaries, chair, `forum.read`)
```
Board Risk Committee — Resolutions                         2026 ▾   [ Check the register ]
Number          Meeting      Item                         Outcome                      Memo
BRC/2026/016    BRC 3/2026   6  Restricted                Restricted                   Restricted
BRC/2026/015    BRC 3/2026   8  Committee terms of ref.   Approved                     —
BRC/2026/014    BRC 3/2026   5  Term loan, Delta Textiles Approved with conditions     CR/HO-CR/2026-27/0042
BRC/2026/013    BRC 2/2026   4  Market risk limits        VOID — corrected to Noted    —
```
- "Check the register": "1 to 16 are all accounted for (15 in force, 1 void)."
- A row the viewer may not see in full shows "Restricted" (item 6 was decided after the meeting, so it took the next number).

**AC2. One resolution**
```
Resolution BRC/2026/014                                  In force
Board Risk Committee · BRC 3/2026 · 12 December 2026 · item 5
APPROVED WITH CONDITIONS
It was RESOLVED that …
Conditions: 1. Security perfected before disbursement.   Valid until 10 March 2027
Memo: CR/HO-CR/2026-27/0042 (moved on 12 Dec)     Minutes: circulated, not yet approved
Extracts issued: none                                    [ Issue a certified extract ]
```
**AC3. Issue a certified extract** → "You will certify this extract as Company Secretary. It will say that the minutes have not yet been approved. [ Cancel ] [ Confirm with your authenticator ]" → the PDF opens; the extract lists its number and fingerprint so operations can check it in memo.

## Flow AD — Drafting the minutes (secretary)

**AD1. The minutes page** (`/meetings/$id/minutes`; a "Minutes" tab beside the agenda once the meeting is held)
```
Minutes — BRC 3/2026, 12 December 2026                  Draft · approved at the next meeting
Steps:  ● Draft  ○ Chair's review  ○ Circulated for comments  ○ Approved at BRC 4/2026  ○ Approved
───────────────────────────────────────────────────────────────────────────────────────────
No draft yet. It will be made from the committee's template with the attendance, declarations,
decisions and your notes.                                     [ Make the draft ]
⚠ Item 6 has no decision. Record one before you send the minutes to the chair.
```
- After "Make the draft" (a few seconds): "Draft ready (version 1, 12 Dec 15:20)". Buttons: [ Open in the editor ] [ See the copy for… ▾ ] [ Send to the chair ].

**AD2. In the editor** (ONLYOFFICE, full screen, "Back to the minutes" always visible). Each item is a framed block titled "Item 5" that cannot be deleted; the secretary types freely inside and between blocks. The template's own headings, tables and fonts are used.

**AD3. The structure banner** (only when something is wrong, after a save)
```
⚠ The block for item 6 is missing. Copies for people who declared an interest cannot be made
  until it is back.                                         [ Restore item 6 ]
```
- **Remake one item:** each item in the side list has ⋯ → "Fill item 5 again from the decision and notes" ("Your edits to item 5 will be replaced"). "Make the whole draft again" asks for confirmation ("All your edits will be replaced").

**AD4. See the copy for…** (a list of people with a different copy)
```
See the copy for:  ( ) Demo Director — item 5 hidden (declared an interest)
                   ( ) S. Ali, R. Das — item 6 hidden (restricted)
                   (•) Everyone else — full minutes
```

**AD5. Send to the chair** → "Dr F. Karim will be asked to review the draft. [ Cancel ] [ Send ]". If the chair declared an interest in an item: "Dr F. Karim declared an interest in item 5, so the vice-chair, Ms N. Chowdhury, will review the draft."

## Flow AE — The chair's review

**AE1. The chair's notice and page** ("The draft minutes of the Board Risk Committee meeting of 12 December are ready for your review"; the meeting page shows the same card)
```
Draft minutes of BRC 3/2026 — ready for your review
[ Read the draft ]   [ Suggest changes in the document ]
Comments to the secretary (optional) [                                             ]
                                    [ Send back with comments ]  [ Ready to circulate ]
```
- "Suggest changes" opens the document in review mode: the chair's changes appear as tracked changes the secretary accepts or rejects.
- The secretary can record "The chair agreed by email on 13 Dec" on the chair's behalf.

## Flow AF — Circulation (members)

**AF1. "Minutes to read" in My meetings and on the meeting page**
```
Board Risk Committee — BRC 3/2026, 12 December
Draft minutes — please send any comments by Thursday 19 December
[ Read the minutes ]            [ No comments ]   [ Suggest a correction ]
```
**AF2. Reading** — the minutes open in the app's PDF viewer, "DRAFT" on every page, "Back to the meeting" always visible. Demo Director's copy says at item 5: "You declared an interest in this item and took no part. The decision: …".

**AF3. Suggest a correction**
```
Suggest a correction
  Which part?  [ Item 5 — Term loan, Delta Textiles ▾ ]   (or "The minutes in general")
  Your suggestion [ My question on the sector outlook is not recorded …        ]
  The secretary and the chair will see this; other members will not.
                                                        [ Cancel ]  [ Send ]
```
- After sending: "Sent on 14 Dec. The secretary will reply here." Replies show under it ("Adopted — K. Secretary: added to item 5").
- "No comments" asks once: "Tell the secretary you have no comments on these minutes? [ Cancel ] [ No comments ]".

**AF4. The secretary's comments panel** (on the minutes page while circulated)
```
Comments — 7 readers: 4 no comments · 1 suggestion · 2 not yet       Deadline 19 Dec  [ Change ]
 S. Ali · item 5 · 14 Dec  "My question on the sector outlook is not recorded …"
                                                       [ Adopt ]  [ Do not adopt ]  note [      ]
[ Ask someone else for comments… ]  (for example the Head of Credit, on items 5 and 7 only)
[ Take back to draft… ]
```
- Edits made while circulated update everyone's copy; members see "Updated on 15 Dec".

## Flow AG — Approval and confirmation

**AG1. At the next meeting** (the next agenda's standing item)
```
 3  Minutes of the previous meeting                                   For decision
     [ 📄 Minutes of BRC 3/2026 (draft) — read your copy ]
```
- The secretary records the decision on item 3 in the AA panel: "Approved" or "Approved with conditions" (with the amendments as conditions), or "Deferred". The minutes page then says "Approved at BRC 4/2026 on 9 January. Apply any amendments, then the chair approves them."

**AG2. By circulation** (management committees): when the deadline passes or everyone has answered, "The comment period ended on 19 Dec: 5 no comments, 1 suggestion (adopted), 1 no answer."

**AG3. The chair's approval** ("The minutes of BRC 3/2026 are ready for your approval")
```
Approve the minutes of BRC 3/2026 (12 December 2026)
  Approved at BRC 4/2026 on 9 January 2027 (amendment to item 5 applied).
  [ Read the final minutes ]
  When you approve, the minutes are locked and kept as the permanent record.
                                    [ Not yet ]  [ Approve the minutes ]  → authenticator code
```
**AG4. The approved minutes page**
```
Minutes — BRC 3/2026, 12 December 2026                        Approved on 9 January 2027
[ Read the minutes ]  (your copy)          Record: PDF/A, 14 pages, fingerprint 3f9a…c21e
Approved by Dr F. Karim, Chair — authenticated with password and authenticator, 1 min before  [ Check ]
Corrections: none                                                     [ Propose a correction ]
```
- A proposed correction goes to the chair for approval with an authenticator code; the record gains a "Corrections" page and a new version; the earlier version stays readable.

## Flow AH — The memo side
**AH1. The note sheet** (the memo's page; the existing FORUM_DECIDED entry gains the number)
```
12 Dec 2026 15:02   Management Credit Committee — APPROVED WITH CONDITIONS
                    Resolution MCC/2026/001: It was RESOLVED that … subject to:
                    1. Security perfected before disbursement.       [ Open the resolution ]
```
- A later wording correction adds: "The committee's decision was corrected on 14 Dec 2026: …".
- **The forum panel** (E02) shows "Decided at MCC 4/2026 — Approved with conditions — MCC/2026/001".
- Memo readers see the outcome, number, text and conditions of their memo's item, never the vote or names.

## Flow AI — The committee type's Minutes tab (flow J gains a "Minutes" tab)
```
Board committee (BC)   Version v4 · Draft
 Policy │ Quorum │ Outcomes │ Terms │ Submissions │ Agenda │ Meetings │ Minutes │ Changes
───────────────────────────────────────────────────────────────────────────────────────
How minutes are approved
  (•) At the next meeting (as "Minutes of the previous meeting")
  ( ) By circulation, then noted at the next meeting
  Members have [ 5 ] working days to comment.  Remind the secretary after [ 10 ] working days.
When a memo moves on after the committee decides
  (•) When the meeting is marked as held
  ( ) When the minutes are approved
Resolution numbers
  [ Committee code ] [ / ] [ Year (2026) ] [ / ] [ Sequence, 3 digits ]   [ + Add part ]
  Start again: (•) every calendar year ( ) every financial year from [ July ▾ ] ( ) never
  Example: BRC/2026/014
Minutes template
  Built-in template   [ Upload a Word template… ]  [ Preview with a sample meeting ]  [ List of tags ]
```
- An uploaded template with a problem is shown in words as for memo templates (ADR-0048): "Line 12 of the body: {{item.discusion}} is not a known tag. Did you mean {{item.notes}}?".
- **Committees** (E01 flow K) gain "Set the starting resolution number…" for go-live (once per year, before the first number; authenticator code).

## Decisions taken under the delegated gate
- **Decisions:** one per item, recorded by the secretary, corrected by new entries with a reason; "took no part" filled from declarations and attendance; recused people cannot be named as voting (ADR-0065).
- **Resolution numbers** are given when the meeting is marked as held, in agenda order, and at once for later decisions; voids are shown in the register (ADR-0065).
- **The recused director's copy** follows ADR-0062: heading, own declaration and the decision.
- **Approved minutes are never edited**; corrections are annexed and approved by the chair (CGI §6.2).
- **Guests and presenters** never read minutes.

## Changes from the overview (reported)
1. **Numbering:** the overview planned "one numbering service" moved out of the memo module. Instead, the format code is shared and resolutions have their own counter, as meeting numbers already do. No memo number or table changes (ADR-0065; research §6.5).
2. **The approval path:** the overview planned approval "through the workflow engine as a new minutes subject". Instead, a fixed path chosen per committee type (chair review → circulation → next meeting or circulation → the chair's approval), shown in "My meetings" (ADR-0066; research §6.1). This is recommendation 3 below.

## Recommendations
Each item has a recommended answer; "as recommended" accepts all of them.
1. **When does a memo move on after the committee decides?** Recommended: **when the secretary marks the meeting as held** (a decision recorded later: at once), with "when the minutes are approved" available per committee type for charters that require it. Why: decisions take effect at the meeting; bank operations act on them the same day; marking the meeting as held gives the secretary time to correct mistakes made in the room.
2. **How are minutes approved?** Recommended: **at the next meeting** for the Board and board committees, **by circulation** (then noted at the next meeting) for management committees such as Credit, ALCO and ExCo, chosen on the committee type. Why: CGI says nearly every organisation approves at the next meeting, and electronic approval is fine if ratified; management committees meet often and need minutes quickly.
3. **Approval path: fixed path or workflow engine?** Recommended: **the fixed path** (draft → chair's review → circulation → approval → chair's approval with authenticator). Why: it is how secretaries work; each reader needs their own copy, which the workflow engine cannot give; fewer moving parts. Named people (for example the Head of Credit) can still be asked for comments.
4. **Resolution numbers.** Recommended: **given when the meeting is marked as held, in agenda order**, format **"BRC/2026/014"** starting again each calendar year, changeable per committee type (financial year, meeting number, other separators). Why: tidy registers in agenda order and no voided numbers for corrections made in the room.
5. **Notes per item during the meeting.** Recommended: **yes**, an optional notes box per item that fills "key points discussed" in the draft; only secretaries see the notes; they are kept but are not part of the record. Why: less typing afterwards (OnBoard, Convene and BoardPro do this).
6. **Vote counts and dissent.** Recommended: **vote counts optional** ("Record a vote"), **named dissent and abstentions always available**, with an optional reason for dissent. Why: most committees decide by consensus (CGI §5.3.4); Bangladesh Bank requires dissent to be recorded in full.
7. **Comment period.** Recommended: **5 working days**, changeable per committee type (1–20); no answer counts as "no comments". Why: CGI's examples run from two days to a week; SS-1 gives seven days and treats silence as approval.
8. **Resolution extracts.** Recommended: **yes**, a certified one-page extract per resolution, issued by a secretary with their authenticator as soon as the memo has moved on, saying whether the minutes are approved yet. Why: operations and outside banks ask for certified extracts; SS-1 allows them before the minutes are signed when the text was before the meeting.
9. **Corrections after approval.** Recommended: **never edit approved minutes**; a correction is proposed by the secretary, approved by the chair, and added as a "Corrections" page in a new version of the record. Why: CGI §6.2; the record stays provably unchanged.

## Owner review
| Flow | Date | Result |
|---|---|---|
| AA–AI (decisions, members' view, resolutions and extracts, drafting, chair review, circulation, approval, the memo side, the type's Minutes tab) | 2026-11-19 | Approved as drawn, with all nine recommendations as written ("ok") |
