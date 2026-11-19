---
status: accepted
date: 2026-11-19
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar (the E06 screens go to the owner before build; two changes from the overview reported: numbering shares the format code, not the counters; the release moment is a committee-type setting)
---

# 0065 — Decisions and resolutions: the decision per item, gapless resolution numbers, and the way back to the memo

## Context and problem statement
Phase 4 E06 closes the loop the exit criterion names: an escalated memo becomes an agenda item (E02, E03), then "a resolution with a number". In Phase 4 there is no electronic voting (overview A3): the secretary records each decision. A decision must carry what the record needs (CGI *Minute taking* §5.3; Basel 2015 ¶60; Bangladesh Bank's 2025 notice on dissent), take the quorum snapshot (ADR-0064) and the recusals (ADR-0062), get a number when its outcome is a resolution (ADR-0056's `resolution` flag), and resume the memo waiting at the forum step (ADR-0058, ADR-0059). The overview planned to generalise ADR-0044's numbering into one shared service. Research: `phase-4-forums-meetings/epic-06-minutes-resolutions/research.md §3–§6`.

## Decision drivers
- The record is append-only (AGENTS §3.3): a correction is a new entry referencing what it corrects.
- Gapless, unique resolution numbers from a counter row locked in the business transaction, never a sequence; voids recorded and never reused (AGENTS §4).
- No change to any existing memo number (overview risk R8).
- A decision takes effect at the meeting (Companies Act 2006 s.249: minutes are its evidence), but a mistake made in the room should be correctable before it reaches the memo.
- Fixed meanings: a tenant label never changes what an outcome does (ADR-0056, ADR-0058).
- Simple for the secretary during a live meeting.

## Considered options
**A. Where numbers come from**
1. **Share the format code, not the counters:** the pure format code moves from `memo.internal` to `com.memo.common.numbering`; resolutions have their own counter table in the minutes module (chosen).
2. One numbering module owning every counter; memo counters migrated into it (the overview's plan).
3. A PostgreSQL sequence per committee (not gapless).

**B. When a number is taken**
1. When the decision is first recorded.
2. **When the meeting is marked as held, in agenda order, and at once for a decision recorded or corrected into a resolution later** (chosen).
3. When the minutes are approved.

**C. When the memo moves on**
1. **A committee-type setting: when the meeting is marked as held (default), or when the minutes are approved** (chosen).
2. Always at once, when the decision is recorded.
3. Always at minutes approval.

## Decision outcome
Chosen options A1, B2 and C1.

### The decision
- **One decision per agenda item** (`minutes_decision`), with an append-only list of **entries** (`minutes_decision_entry`); the latest entry is the decision. Each entry holds:
  - the outcome: one of the pinned committee type version's outcomes (kind and label); the purpose suggests the default (For decision → the first APPROVED kind, For noting → NOTED);
  - the resolution text (0–4,000 characters; required for an outcome marked `resolution`), written as the committee would minute it ("It was resolved that …");
  - **conditions**: 0–20 lines of 1–1,000 characters, required (at least one) for APPROVED_WITH_CONDITIONS; E07 can turn each into an action;
  - an optional **valid until** date (an approval's validity, for example a credit sanction for 90 days);
  - for DEFERRED: to a named meeting or "the next meeting"; for REFERRED: the committee referred to and a reason;
  - **the vote:** CONSENSUS (no vote taken, the default) or VOTE with counts for, against and abstaining. Counts are optional details of a VOTE; their sum may not exceed the voting seats present and not recused in the snapshot;
  - **named dissent** (each a person present, voting and not recused, with an optional reason up to 1,000 characters) and **named abstentions**, allowed with or without a vote (CGI §5.3.3–5.3.4);
  - **took no part:** filled from the snapshot, never typed: the members recused from the item (ADR-0062) and those who stepped out for it (ADR-0064). A recused member can never be named as voting, dissenting or abstaining;
  - **the quorum snapshot** taken in the same transaction (`QuorumSnapshots.take`), at the time the item was taken (the item in progress, ADR-0064, editable within the meeting's window). Not quorate → 409 `NOT_QUORATE`, or E05's override when the committee type allows it, which marks the decision "to be ratified";
  - optionally the decision it **ratifies** (a later, quorate decision confirming one taken without a quorum);
  - who recorded it, when, and for a correction the reason (1–500 characters).
- **Who records:** a secretary of the committee or a `forums.manage` holder, not recused from the item (403 `RECUSED`). The chair does not record.
- **When:** while the meeting is IN_PROGRESS or HELD, until the minutes leave the draft (ADR-0066). A correction once the minutes are with the chair or circulated sends the minutes back to the draft (the item's text must be regenerated). After the minutes are approved, decisions are final.
- **Consent sections:** one action decides every item of a consent section still in it (not lifted, ADR-0060) with each item's default outcome; it writes one decision per item with a shared batch id and one snapshot per item.
- **Standing items** need no decision, except "Minutes of the previous meeting", whose decision approves those minutes (ADR-0066).
- **Locks for other modules:** the meeting module publishes a `DecidedItems` port (default: nothing decided) that the minutes module implements, so E04's declarations on a decided item change only by the secretary's correction (E04 design §1.1) without the meeting module depending on minutes.

### Resolution numbers
- **The format** is part of the committee type version (maker-checker, ADR-0056): the parts TEXT, COMMITTEE_CODE (the forum's code), YEAR (2 or 4 digits), FINANCIAL_YEAR (with a start month), MEETING_NUMBER (the meeting's number in its series) and SEQUENCE (minimum digits); a reset NEVER, YEARLY or FINANCIAL_YEAR. Default `{COMMITTEE_CODE}/{YEAR:4}/{SEQUENCE:3}`, for example `BRC/2026/014`, yearly.
- **The counter** is one row per committee and period (`minutes_resolution_counter`), taken with `INSERT … ON CONFLICT DO UPDATE … RETURNING` in the business transaction and locked until commit. The period is the **meeting's date in its time zone**, so a resolution belongs to its meeting's year even when recorded on 2 January.
- **When:** the meeting's end (HELD) schedules the `minutes-release` job, which numbers every current resolution decision of the meeting **in agenda order** (the latest revision's item numbers; tabled items after the agenda), one transaction per decision. A decision recorded or corrected into a resolution outcome after HELD is numbered in its own transaction. During the meeting the screen says "Numbered when the meeting ends".
- **Voids:** a number, once taken, belongs to its decision. If a correction (before release) changes the outcome to one that is not a resolution, the resolution becomes VOID with the correction's reason; the number is never reused. The register shows it.
- **A starting number** may be set once per committee and period before its first number is taken (to continue a paper register at go-live), by a `forums.manage` holder with a fresh step-up; audited; refused after (`ALREADY_STARTED`), as ADR-0044.
- **The register** per committee and period lists every number in order with its meeting, item, outcome, memo and state; a check compares the count with the counter (on demand and in the nightly suite).
- **Shared code:** `Numbering`'s parts, periods, rendering and checks move to `com.memo.common.numbering` with a code resolver per owner (the memo type's code and unit for memos; the committee's code and meeting number for resolutions). The memo module keeps its JSON, its enum names and its `memo_number_counter`; its numbering tests and concurrency suite run unchanged. **No memo number or table changes.**

### Back to the memo
- **Release** sends each decision on a submission item to its submission through the forum module's new published `ForumDecisions` API, in the release transaction:

  | Outcome kind | Submission | Waiting memo (ADR-0058) |
  |---|---|---|
  | APPROVED, APPROVED_WITH_CONDITIONS, NOTED | DECIDED | `ForumDecided` with that outcome: the route continues |
  | REJECTED | DECIDED | `ForumDecided(REJECTED)`: the instance ends rejected |
  | RETURNED | DECIDED | `ForumDecided(RETURNED)`: back to the initiator |
  | WITHDRAWN (withdrawn at the meeting by its presenter) | DECIDED | `ForumDecided(RETURNED)` with "Withdrawn at the meeting" |
  | DEFERRED | DEFERRED (placement cleared, target the named meeting or date) | keeps waiting |
  | REFERRED | REFERRED (a new submission to the other committee, linked) | keeps waiting, now for the other committee |

  A pass-through submission (`waits = false`) is closed as DECIDED (noted) whatever the outcome.
- **The remark** on `ForumDecided` carries the outcome label, the resolution number and text and the conditions; the action's `details` gain `resolution` (the number) and `decisionId`. The note sheet already shows FORUM_DECIDED with the committee's name; it gains the number with a link, and so the memo's final record (ADR-0053) carries it in the approval history and note sheet. No memo migration.
- **When (committee-type setting `minutes.memoResumes`):**
  - **AT_MEETING_END** (default): decisions recorded during the meeting are released by the `minutes-release` job when the meeting is marked as held; a decision recorded after that is released at once. The secretary is told before marking the meeting as held and before recording after it: "The memo will move on at once."
  - **ON_MINUTES_APPROVAL:** every decision of the meeting is released by the same job when the minutes are approved.
- **After release** the outcome is fixed: a correction that changes the outcome kind answers 409 `RELEASED` ("The memo has already moved on. Record the correction in the minutes; the author can resubmit the memo."). Text, conditions, the vote and names may still be corrected until the minutes are approved; the memo's note sheet gets a system note "The committee's decision was corrected" with the new text.
- Release is idempotent: each decision records `releasedAt` and the outcome released; a retry skips released decisions; the submission's own state guard (QUEUED or ACCEPTED) is the last line.

### Consequences
- Good: every fact CGI, Basel and Bangladesh Bank ask the record to hold is a field, not free text; recused members can never be named as voting.
- Good: numbers are gapless and in agenda order; a mistake made in the room costs nothing until the meeting ends; no memo number is touched.
- Good: banks' operations get the decision on the day of the meeting by default, and a committee whose charter says otherwise can wait for the minutes.
- Bad: two counter tables (memo, resolution) and a third for meeting numbers, rather than one service. Each is small and follows the same pattern; the format code is shared.
- Bad: an outcome mistake found after release needs the author to resubmit. The minutes record the correction (CGI §6.2's "matters arising").
- Bad: `ForumDecisions` widens the forum module's published API by three operations (decide, defer at a meeting, refer at a meeting).
- Follow-ups: electronic votes (Phase 5) write the counts and names instead of the secretary; E07 raises actions from conditions; E08 builds the cross-forum register and search on these rows.

### Confirmation
- `ResolutionNumberingConcurrencyIT` (Phase-4 special test): parallel meetings ending at once and decisions recorded concurrently after HELD produce gapless, unique numbers per committee and period; a rolled-back release leaves no gap; voids are listed; the register count equals the counter.
- Property test: for random agendas and recording orders, numbers follow agenda order at HELD.
- PIT on numbering and release mapping; state-machine tests for every allowed and forbidden decision transition; matrix `p4-e06-decisions.yaml` with a recused-member column.
- `ForumEscalationIT` extended: escalate → place → decide → held → memo resumed with the number on its note sheet and in its final record.

## Pros and cons of the options
- **A1:** + nothing in memo numbering changes; each module migrates alone; − three counter tables. **A2:** + one place; − a two-release migration of live memo counters for no user benefit. **A3:** − not gapless.
- **B1:** + simple; − numbers out of agenda order when items are recorded out of order, and voids for every correction in the room. **B2:** + agenda order, no voids for in-room corrections; − numbers are shown only after the meeting. **B3:** − no number for extracts or the memo until weeks later.
- **C1:** + fits both bank operations and stricter charters; − one more setting. **C2:** − no buffer for mistakes made in the room. **C3:** − credit approvals wait weeks.

## More information
- CGI, *Minute taking* (2017): <https://cgi.org.uk/media/mpjeexxj/minute-taking.pdf> (accessed 2026-11-19).
- Basel Committee (2015) ¶60: <https://www.bis.org/publications/201507-guidelines-corporate-governance-principles-banks.pdf>.
- Bangladesh Bank BRPD notice, 4 August 2025, as reported: <https://www.tbsnews.net/economy/banking/bb-mandates-recording-dissenting-opinions-board-meetings-1204361>.
- Companies Act 2006 ss.248–249: <https://www.legislation.gov.uk/ukpga/2006/46/section/249/data.htm>.
- Related: ADR-0044 (memo numbering), ADR-0056 (outcomes), ADR-0058 and ADR-0059 (the waiting step and submissions), ADR-0060 (consent sections), ADR-0062 (recusal), ADR-0064 (snapshots and the override), ADR-0066 (minutes).
