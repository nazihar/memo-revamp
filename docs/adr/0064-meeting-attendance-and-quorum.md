---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar (reported: the quorum override moves from a tenant setting to the committee type, and alternates never meet a required role)
---

# 0064 — Attendance and quorum at the meeting: an append-only register, live evaluation per item, snapshots with each decision; amends ADR-0057

## Context and problem statement
ADR-0057 defined the quorum rule and one pure evaluator, and left to E05 "losing quorum mid-meeting, and overriding a missing quorum". ADR-0062 (E04) supplies recusals per item and left open whether a recused member's alternate stands in. The secretary must record who is there, how and when; the chair must know at every moment whether the meeting and the item in hand are quorate; and every decision (E06) must carry the quorum it was taken with. Three behaviours of today's `Quorum.evaluate` also need deciding: an alternate's seat satisfies required roles and composition attributes, and one person can count for several seats. Research: `phase-4-forums-meetings/epic-05-invitations-attendance-quorum/research.md §5`.

## Decision drivers
- What the minutes need (CGI *Minute taking* §5.1, §5.3.6): present in person, remotely, as an alternate, in attendance, apologies, absent, and who was not there for the whole meeting.
- "The company secretary [must] be aware whether the meeting is quorate at all times" (CGI §5.2.1); no substantive business without a quorum (Robert's Rules).
- The record is append-only (AGENTS §3.3); the live view and the recorded snapshot must agree (Phase 4 special test).
- Alternates: the UK model articles for public companies, art. 25–26: an alternate acts in the appointor's absence, is "subject to the same restrictions", counts "only if that person's appointor is not participating", and never "as more than one director".
- Configuration over code (AGENTS §3.5); safe defaults; maker-checker for policy.

## Considered options
1. **An append-only attendance register per person, live evaluation from it, and an immutable snapshot taken by each decision** (chosen).
2. A mutable attendance list (one status per person), evaluated when a decision is recorded.
3. Quorum confirmed once at the start of the meeting (a tick by the secretary), as BoardPro's single percentage does.

## Decision outcome
Chosen option 1.

### Attendance
- **The register** has one current row per person who may attend: roster members in office on the meeting's date, alternates standing in, guests, item attendees and officers. Its state is EXPECTED (nothing recorded), PRESENT (mode IN_PERSON or REMOTE), APOLOGIES or ABSENT, and it is derived from **append-only events**: APOLOGIES, ABSENT, ARRIVED (mode, time), MODE_CHANGED, LEFT (time), STEPPED_OUT and STEPPED_BACK (for one item), and VOIDED (a correction, with a reason, naming the event it cancels).
- Before the meeting the register is filled from the RSVPs (ADR-0063): apologies are recorded as APOLOGIES; "yes" answers are expected, not present. The secretary records arrivals at the meeting, in one action for everyone who said yes if they wish.
- **Times and items:** each event has the time it happened (default now, editable within the meeting's window) and the item in progress then. The secretary marks the item in progress ("Now: item 5"); arrivals and departures are then shown as "joined during item 4" and "left after item 7", which is what the minutes say.
- **Windows:** apologies from scheduling until the start; arrivals, departures and modes while IN_PROGRESS; after HELD and until the minutes are approved (E06), corrections only, each with a reason, audited as corrections. Then frozen.

### Alternates
- A member who sends apologies can say "my alternate will attend" when their seat has one (ADR-0056). The alternate joins the meeting's audience as ALTERNATE for that member, is invited (ADR-0063) and reads what the member would read.
- **An alternate counts only while their member is absent.** If the member arrives, the alternate stays "in attendance" and stops counting until the member leaves.
- **An alternate counts once**, whoever they stand for, and a person present as a member cannot also stand in (art. 26(3)).
- **An alternate counts for the number only.** Required roles (for example "the chair or a vice-chair") are met only by the seat holder; composition attributes (for example "independent") are the person's, so an alternate does not carry them. The quorum is never overstated (HSBC: "the Chair or their delegate from among the members").
- **A recused member's alternate does not stand in for that item** and is recused from it with them: no papers, not counted (art. 26(2) "subject to the same restrictions"). This answers ADR-0062's open question. A pool of alternates for disqualified members (New Hampshire boards) is not offered in Phase 4.

### Quorum
- **Inputs** at instant *t*, for the meeting or for item *i*: the pinned type version's rule (ADR-0060), the roster in office on the meeting's date, and each seat's presence at *t*: present in person or remote, by alternate, stepped out of *i*, recused from *i* (ADR-0062 `Recusals`, including a recused member's alternate).
- **Evaluation** is `Quorum.evaluate`, unchanged in signature. **This ADR amends ADR-0057:** a seat present by alternate counts towards the needed number when `alternatesCount` is set, but never satisfies a `required` role set or a `composition` condition; callers pass at most one stand-in per person.
- **Live:** the secretary and the chair see the meeting's quorum and the current item's at all times, with what is missing in words ("Not quorate: 3 of 4 needed; no independent member present"). A change from quorate to not quorate, or back, while IN_PROGRESS is recorded and audited as "quorum lost" or "quorum restored" with the time and item.
- **Forecast:** before the meeting, the same evaluation over the RSVPs ("If everyone who said yes attends") and over the recusals, shown to officers, with a warning to the secretaries and the chair when the forecast falls short at the reply deadline.
- **Recorded:** `QuorumSnapshots.take(meeting, item, at)` stores the rule, the roster, the presence and recusals used, the result and a SHA-256 of the canonical inputs, append-only. E06 calls it in the decision's transaction and keeps the snapshot id with the decision. A correction of attendance later never changes a snapshot; E06 shows "attendance was corrected after this decision" and a corrected decision takes a new snapshot.
- **Without quorum** a decision is refused (409 `NOT_QUORATE`). A committee type may set `quorumOverride: WITH_REASON` (default `NEVER`). Then a secretary not recused from the item may record the decision with a reason; the snapshot says not quorate, overridden, by whom, why, and **"to be ratified at a quorate meeting"**, and the chair and the other secretaries are told. **This changes the overview**, which made the override a tenant setting: a committee type is reviewed by a second administrator (ADR-0056), so allowing an override needs two people, and charters differ by committee.

### Consequences
- Good:
  - The minutes (E06) get every fact CGI lists, from one register.
  - The secretary always sees the quorum per item, with recusals and departures, and the record of each decision cannot drift from what was shown.
  - Alternates follow the company-law model: they fill a seat's number, not its role or its independence, and never escape their member's conflict.
  - Overrides are rare, reviewed policy, reasoned, visible and marked for ratification.
- Bad:
  - The secretary has more to record during the meeting. Mitigated by pre-filling from RSVPs, "mark everyone who said yes as present", and tie-to-current-item defaults.
  - A charter that lets an alternate act as chair for quorum purposes needs a new rule building block; none of the researched charters does.
  - Live updates are fetched every 15 seconds on the attendance and quorum views; real-time push comes in Phase 5.
- Follow-ups:
  - E06: the decision takes a snapshot; the minutes template gets attendance, alternates, arrivals, departures, quorum lost and restored, and "decided without a quorum, to be ratified".
  - E07 or later: a standing "ratification" item suggested for the next meeting.
  - E08: attendance rates from the register.

### Confirmation
- Property tests over random rules × rosters × attendance × recusals × alternates: monotonic (an arrival never breaks quorum; a departure or a recusal never creates it; but for a composition majority, which is of those counted present); an alternate never satisfies a required role or a composition condition; a recused member's alternate never counts for that item; a person never counts twice; the live evaluation at *t* equals a snapshot taken at *t*.
- PIT on the presence builder and `Quorum`.
- State-machine tests for every allowed and forbidden attendance event per meeting status and person state.
- Integration tests that a snapshot's stored inputs re-evaluate to its stored result and hash.

## Pros and cons of the options
- **Option 1:** + complete record, per-item truth, snapshots that cannot drift; − more to record live, more tables.
- **Option 2:** + simple; − loses arrivals and departures, cannot answer "was item 5 quorate", and rewrites history.
- **Option 3:** + minimal effort; − ignores departures and recusals, which are the usual reasons quorum is lost (CGI §5.2.1).

## More information
- CGI, *Minute taking* (2017): <https://cgi.org.uk/media/mpjeexxj/minute-taking.pdf> (accessed 2026-11-18).
- Robert's Rules FAQ: <https://robertsrules.com/frequently-asked-questions/> (accessed 2026-11-18).
- Companies (Model Articles) Regulations 2008, sch. 3, art. 25–26: <https://www.legislation.gov.uk/uksi/2008/3229/schedule/3> (accessed 2026-11-18, through search results).
- ADR-0056 (forums and alternates), ADR-0057 (quorum rules, amended here), ADR-0062 (recusal), ADR-0063 (invitations and RSVP).
