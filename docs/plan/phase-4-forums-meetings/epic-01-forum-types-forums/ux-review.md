# E01 Forum types and forums — UX review

> `AGENTS.md §5` Step 3 and Phase 4 Q1: the screens for committee types and committees go to the owner **before build** (T4, T5). Wireframes are structural; the look is the existing Ledger direction and components. "Committee" is the default English word for a forum (terminology term `forum`, renameable per tenant).

## Design principles taken from the research
1. **Membership drives access.** Nobody sets permissions per committee or per meeting. Being on the committee is the permission (OnBoard's most common complaint, research §3.1).
2. **Role, voting and access are separate.** A role is what the charter says. Voting is its own tick, defaulted from the role.
3. **Quorum in one sentence.** The rule is built from plain choices and read back as the sentence a charter would use, with a worked example and a tester.
4. **Start from an example.** Presets for the Board, a board committee and a management committee, and "Clone this committee".
5. **History is never lost.** Renewing or changing a role adds a row; ending keeps the record.
6. **Large targets, plain words, one primary action per screen**, so it works for older, non-technical users.

## Flow J — Committee types (Administration)

**J1. List** (`/admin/committee-types`)
```
Committee types                                         [ New committee type ]
┌───────────────────────────┬───────┬───────────┬────────────────────────┬────────┐
│ Name                      │ Code  │ Published │ Changes                │ Status │
├───────────────────────────┼───────┼───────────┼────────────────────────┼────────┤
│ Board                     │ BOARD │ v1        │ —                      │ Active │
│ Board committee           │ BC    │ v2        │ In review v3 · Admin   │ Active │
│ Management committee      │ MC    │ v1        │ Draft v2               │ Active │
└───────────────────────────┴───────┴───────────┴────────────────────────┴────────┘
```
"New committee type" asks for **name**, **code** and **Start from**: Blank, Board, Board committee or Management committee. It then opens the type page on draft v1.

**J2. Type page** (`/admin/committee-types/$id`): one page, tabs, a version selector, the same toolbar as memo types
```
Board committee (BC)             Version: [ v3 · In review ▾ ]   Published: v2
Submitted by Admin, 18 Nov 10:20                  [ Send back ] [ Approve ]
Changes apply to every committee of this type from their next meeting.
──────────────────────────────────────────────────────────────────────────────
 Policy │ Quorum │ Outcomes │ Terms │ Changes (3)
```

**J3. Policy tab**
```
Name                    [ Board committee                      ]
Description             [ Committees of the Board under their terms of reference ]
Default classification  [ Confidential ▾ ]
Keep records for        [ 10 ] years after the meeting       (kept now, acted on later)
Member attributes       Used in quorum rules and shown on the roster
  [x] Independent            key: independent
  [x] Non-executive          key: nonExecutive
  [ ] Management             key: management
  [ + Add attribute ]
```

**J4. Quorum tab**: the builder on the left, the sentence, example and tester on the right (stacked on a phone)
```
Who counts        (•) Voting members   ( ) All members (observers and standing invitees never count)
Out of            (•) Members in office on the day   ( ) A fixed number of seats [   ]
How many          (•) More than half   ( ) A fraction [ 2 ] / [ 3 ] rounded up   ( ) A fixed number [  ]
Never fewer than  [ 2 ]
Must include      [ Chair ✕ ][ Vice-chair ✕ ]  (any one of them)   [ + Add another requirement ]
Of those present  at least [ 2 ] must be [ Independent ▾ ]   [ + Add condition ]
                  [x] Alternates count for the member they replace
                  [x] Members attending remotely count
                  [ ] Members who declared a conflict on an item count for that item

┌ In words ───────────────────────────────────────────────────────────────────┐
│ More than half of the voting members in office, and never fewer than 2,     │
│ including the chair or a vice-chair, with at least 2 independent members.   │
├ Example ─────────────────────────────────────────────────────────────────────┤
│ With 7 voting members in office, 4 must attend.                              │
├ Try it ──────────────────────────────────────────────────────────────────────┤
│ Voting members in office [ 7 ]   Present [ 4 ]   Chair present [x]           │
│ Independent among them [ 1 ]                                                 │
│ ✕ Not quorate: needs 2 independent members, has 1.                           │
└──────────────────────────────────────────────────────────────────────────────┘
```

**J5. Outcomes tab**: the decisions this committee records; labels are yours, meanings are fixed
```
On │ Meaning (fixed)               │ Label                           │ Numbered as a resolution
[x]│ Approved                      │ [ Approved                    ] │ [x]
[x]│ Approved with conditions      │ [ Approved subject to         ] │ [x]
[x]│ Rejected                      │ [ Declined                    ] │ [x]
[x]│ Deferred                      │ [ Deferred to a later meeting ] │ [ ]
[x]│ Returned for more information │ [ Returned to the proposer    ] │ [ ]
[ ]│ Referred to another committee │ [ Referred                    ] │ [ ]
[x]│ Noted                         │ [ Noted                       ] │ [ ]
[ ]│ Withdrawn                     │ [ Withdrawn                   ] │ [ ]
ⓘ What each meaning does to a memo waiting for the decision is explained in the help text.
```

**J6. Terms tab**
```
Usual term of office     [ 36 ] months    (filled in when a member is added; can be changed per member)
Most consecutive terms   [ 3 ]            (a warning on renewal, never a block)
Remind the secretary     [ 42 ] and [ 1 ] days before a term ends
```

**J7. Changes tab** (opened first for the reviewer): "before → after" lines, for example "Quorum: more than half → two-thirds, rounded up" or "Outcome 'Approved' label: Approved → Sanctioned".

## Flow K — Committees (Administration and secretaries)

**K1. List** (`/committees`): everyone sees the committees visible to them; administrators see all and the "New committee" button
```
Committees                                                        [ New committee ]
┌──────────────────────────────┬──────┬─────────────────────┬─────────┬──────────┬────────┐
│ Name                         │ Code │ Type                │ Chair   │ Members  │ Status │
├──────────────────────────────┼──────┼─────────────────────┼─────────┼──────────┼────────┤
│ Board of Directors           │ BOD  │ Board               │ R. Khan │ 9        │ Active │
│ Board Risk Committee         │ BRC  │ Board committee     │ S. Ali  │ 5  ⚠ 1   │ Active │
│ Management Credit Committee  │ MCC  │ Management committee│ Head of Credit │ 6 │ Active │
└──────────────────────────────┴──────┴─────────────────────┴─────────┴──────────┴────────┘
⚠ 1 = one term ends within the reminder period or has ended
```
"New committee" asks for **type**, **name**, **short name**, **code**, **workflow key** (suggested from the name), **reports to** (optional) and **classification** (defaults from the type).

**K2. Committee page** (`/committees/$id`)
```
Board Risk Committee (BRC)                 Board committee · Confidential · Reports to: Board
Secretary: Company Secretary              [ Add member ]  [ ⋯ Clone · Edit · Dissolve ]
──────────────────────────────────────────────────────────────────────────────
 Members │ History │ Policy
──────────────────────────────────────────────────────────────────────────────
Members on [ 18 Nov 2026 ▾ ]                                  Quorum today: 3 of 5
┌───────────────────┬────────────┬────────┬──────────────┬─────────────┬──────────────────┬────┐
│ Name              │ Role       │ Votes  │ Attributes   │ Alternate   │ Term             │    │
├───────────────────┼────────────┼────────┼──────────────┼─────────────┼──────────────────┼────┤
│ S. Ali            │ Chair      │ Yes    │ Independent  │ —           │ 2nd · to 30 Jun 28│ ⋯ │
│ Demo Director     │ Member     │ Yes    │ Independent  │ F. Rahman   │ 1st · to 31 Dec 26│ ⋯ │
│                   │            │        │              │             │ ⚠ ends in 42 days │    │
│ Chief Risk Officer│ Member     │ Yes    │ Management   │ —           │ ex officio        │ ⋯ │
│  (now: N. Hossain)│            │        │              │             │                   │    │
│ Company Secretary │ Secretary  │ No     │ —            │ —           │ —                 │ ⋯ │
│ Head of Audit     │ Standing invitee │ No │ —           │ —           │ —                 │ ⋯ │
└───────────────────┴────────────┴────────┴──────────────┴─────────────┴──────────────────┴────┘
Row menu ⋯ : Renew term · Change role · Edit · End membership   (Withdraw for upcoming rows)
```
- **History** lists every row, newest first: "S. Ali — Member, 1 Jul 2022 – 30 Jun 2025 (1st term) → Chair from 1 Jul 2025 (2nd term)", with the authority text.
- **Policy** shows the type's published policy read-only (the quorum sentence, outcomes, terms), with a link to the type for administrators.

**K3. Add member** (side sheet; full screen on a phone)
```
Add a member to Board Risk Committee
Who        (•) A person [ Search people…            ]
           ( ) A position, whoever holds it [ Search positions… ]   e.g. Chief Risk Officer
Role       [ Member ▾ ]        Votes  [x]   (set from the role; change it if the charter differs)
Attributes [x] Independent  [ ] Non-executive
Alternate  [ Search people… ]   (attends and counts when the member sends apologies)
From       [ 01/12/2026 ]   Term ends [ 30/11/2029 ]  (36 months, from the type)
Authority  [ Board resolution of 12 Oct 2026            ]  (optional)
                                                   [ Cancel ]  [ Add member ]
```
Secretaries see the same sheet. Chair and Secretary are missing from the role list for them, with a hint: "Only an administrator can appoint a chair or a secretary."

**K4. Renew / Change role / End**: small dialogs
- Renew: "Start term 3 on 1 Jul 2028, ending [date]". Shows a warning when the type's most-consecutive-terms limit is passed.
- Change role: new role, from date, votes.
- End: date and reason (Resigned, Term completed, Removed, Other + text).

**K5. Clone** (from ⋯): new name, short name, code, key; "Copy current members, starting on [date]".

## Flow L — My committees and the workflow builder

**L1. My committees** (`/me/committees`, also a card on the home page when the user sits on any)
```
My committees
┌──────────────────────────────────────────────────────────────┐
│ Board Risk Committee          Member · votes · Independent    │
│ Term 1 ends 31 Dec 2026                                        │
│ Chair: S. Ali   Secretary: Company Secretary   5 members       │
│ [ Open ]                                                       │
└──────────────────────────────────────────────────────────────┘
```
Meetings and papers appear on this card from E03.

**L2. Workflow builder**: the "Escalate to committee" step gets a **picker** of active committees (name, code) in place of the free-text key. A definition imported with an unknown key shows "This committee does not exist or is dissolved" on the step.

## Decisions taken under the delegated gate
- The UI says "committee" and "committee type", renameable through terminology.
- Membership changes need no second approval (research §3.2). The chair and secretaries are told of every change.
- An expired term is flagged and stops counting. It is never removed automatically.

## Owner review
| Flow | Date | Result |
|---|---|---|
| J committee types | 2026-11-18 | Approved as drawn ("ok") |
| K committees | 2026-11-18 | Approved as drawn ("ok") |
| L my committees, builder picker | 2026-11-18 | Approved as drawn ("ok") |
