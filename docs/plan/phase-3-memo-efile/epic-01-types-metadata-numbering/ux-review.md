# E01 Memo types, metadata and numbering — UX review

> `AGENTS.md §5` Step 3 and Phase 3 Q1: the screen flows for the memo-type admin and the create form go to the owner **before build** (T6, T7). Wireframes are structural; the look is the existing Ledger direction and components.

## Flow A — Memo types (admin)

**A1. List** (`/admin/memo-types`)
```
Memo types                                              [ New memo type ]
┌──────────────────────┬──────┬───────────┬────────────────────────┬─────────┐
│ Name                 │ Code │ Published │ Changes                │ Status  │
├──────────────────────┼──────┼───────────┼────────────────────────┼─────────┤
│ General memo         │ GEN  │ v1        │ —                      │ Active  │
│ Credit facility      │ CR   │ v3        │ In review v4 · Admin   │ Active  │
│ Expense approval     │ EXP  │ v2        │ Draft v3               │ Active  │
│ Old procurement      │ PRC  │ v1        │ —                      │ Off     │
└──────────────────────┴──────┴───────────┴────────────────────────┴─────────┘
```
"New memo type" asks for **name** and **code** only, then opens the type page on draft v1.

**A2. Memo type page** (`/admin/memo-types/$id`): one page, tabs, version selector
```
Credit facility (CR)            Version: [ v4 · In review ▾ ]   Published: v3
Submitted by Admin, 14 Nov 10:20                 [ Send back ] [ Approve ]
──────────────────────────────────────────────────────────────────────────────
 Settings │ Form │ Numbering │ Changes (5)
──────────────────────────────────────────────────────────────────────────────
```
- Toolbar by state and role. Draft (maker): **Save**, **Submit for review**. In review (checker): **Send back** (remark required), **Approve** (asks for the authenticator code if the last one is old). Published: **New draft**.
- **Changes** opens first for the checker: a list of "before → after" lines (field added, rule changed, format changed), like the workflow review.

**A3. Settings tab**
```
Name               [ Credit facility            ]
Code               [ CR ]   used in numbers
Workflow           [ Credit approval (fixed) ▾ ]   Route: ( ) chosen by initiator (•) fixed ( ) both
Approvers allowed  (people, positions, roles — as today)
Check DoA before submit   [x]
Ask for MFA before approve [x]
Default classification [ Internal ▾ ]
Who may raise      (•) Everyone who can create memos
                   ( ) Only: [ Units… ] [ Roles… ]
Retention          [ 10 ] years from ( ) approval (•) closure      (kept now, acted on later)
Close automatically [ 30 ] days after approval   (empty = never)
Attachments        [x] At least [ 1 ] attachment required          (enforced when attachments arrive)
```

**A4. Form tab**: field list on the left, live preview of the create form on the right (stacked on a phone)
```
Fields                                   │ Preview
 1 Amount            Amount · required   │  Subject *        [                    ]
   ↑ ↓ ✎ ✕                               │  Classification   [ Internal ▾ ]
 2 Purpose           Choice · required   │  Amount *         [ 0.00      ] [BDT ▾]
   ↑ ↓ ✎ ✕                               │  Purpose *        ( ) Working capital
 3 Customer unit     Unit                │                   ( ) Term loan
   ↑ ↓ ✎ ✕                               │  Customer unit    [ Search units…    ]
 4 Expected date     Date                │  Expected date    [ dd/mm/yyyy ]
   ↑ ↓ ✎ ✕                               │
[ + Add field ]                          │
```
Add or edit opens a **side sheet**:
```
Field
Label        [ Amount               ]
Key          [ amount ]   used in workflow conditions; cannot change once published
Help text    [ Total facility amount ]
Type         [ Amount ▾ ]  (Text, Long text, Number, Amount, Date, Unit, Person, Choice, Multiple choice)
Required     [x]
Currencies   [ BDT ] [ USD ] [ + ]          Decimals [ 2 ]   Min [ 0 ]  Max [      ]
Use in workflow rules   [x]   → item.amount, item.amountCurrency
Show in inbox           [ ]   (used when the inbox shows fields, later)
                                              [ Cancel ] [ Save field ]
```
- Reordering uses ↑ ↓ buttons (no drag and drop, as in the route editor).
- Field types with options (Choice, Multiple choice) show an ordered option list (key and label).

**A5. Numbering tab**
```
Format     [CR] [ / ] [Unit code] [ / ] [Financial year ▾ 2026-27] [ / ] [Sequence: 4 digits]
           [ + Text ] [ + Type code ] [ + Unit code ] [ + Year ] [ + Financial year ] [ + Sequence ]
Series     [ credit ]    shared with: —        (types with the same series share one register)
Restart    ( ) never  ( ) every year  (•) every financial year, starting [ July ▾ ]
Count      (•) one sequence for the series   ( ) one sequence per unit
Number when (•) submitted   ( ) finally approved
Sample     CR/HO-CR/2026-27/0001   next year: CR/HO-CR/2027-28/0001
```

**A6. Numbering overview and register** (`/admin/memo-numbering`)
```
Series   Periods      Last number   Used by
general  2025, 2026   M-2026-00418  General memo, Old procurement
credit   2026-27      0042 (HO-CR)  Credit facility

credit · 2026-27 · HO-CR                                      [ Check completeness ]
 Number                   Memo                   Status       Note
 CR/HO-CR/2026-27/0001    Working capital line…  Approved
 CR/HO-CR/2026-27/0002    (not visible to you)   Withdrawn    Void — "Duplicate request"
```
**Set starting number** appears only for a period with no numbers yet (to continue a legacy series). It asks for the authenticator code.

## Flow B — Create and view a memo

**B1. New memo** (`/memos/new`): choose the type first
```
New memo
What kind of memo?
 ( ) General memo        Any internal approval
 (•) Credit facility     Credit proposals; numbered on submit
 ( ) Expense approval    Spending within budget
 ...                     (more than 6 types → a searchable list instead)
```

**B2. Then one form** (the same form the admin previewed)
```
Subject *          [                                         ]
Classification     [ Internal ▾ ]
── Credit facility details ──
Amount *           [ 6,000,000.00 ] [ BDT ▾ ]
Purpose *          (•) Working capital  ( ) Term loan
Customer unit      [ Motijheel branch          ▾ ]
Expected date      [ 30/11/2026 ]
                                     [ Cancel ] [ Save draft ]
```
- Save draft works with missing required fields. Submitting later lists what is missing and jumps to the first gap.
- After saving: the memo page (document upload and route, as today).

**B3. Memo view additions**
```
Unnumbered — numbered when submitted                      Draft
Credit facility · Internal
Details
  Amount          BDT 6,000,000.00
  Purpose         Working capital
  Customer unit   Motijheel branch
  Expected date   30 Nov 2026
Linked memos      CR/HO-CR/2026-27/0007 (refers to)   [ + Link a memo by number ]
Linked from       —
```
- After submit, the number replaces "Unnumbered".
- Approved memos get a **Close** action.
- A draft whose type changed shows the notice: "This memo type changed on 14 Nov. Check the details before you submit." Values for removed fields appear under "No longer on this form".

## Questions for the owner
1. Are a **side sheet** for editing a field and a **live preview** beside the list the right way to build forms, or would you prefer a single long page?
2. Should **number when finally approved** be offered at all, or should every type number on submit (simpler, and approvers always have a number to cite)?
3. **Closing:** should approved memos close only by hand, or automatically after N days per type (both are planned)?

## Owner review
| Date | Reviewer | Outcome |
|---|---|---|
| — | Product owner | Waiting |
