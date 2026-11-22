---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar
---

# 0057 — Quorum rules: a structured rule, evaluated by one pure function

## Context and problem statement
Every forum type states when its meetings are quorate. E01 stores and previews the rule. E05 evaluates it live and records it with each decision, and E04's recusal changes it per item. Charters write quorum in one sentence, but the sentences vary: a majority, two-thirds rounded up, "two members including the chair or their delegate", "a majority of independents", "at least two board and two management members". Jurisdictions disagree on whether conflicted members count. Research: `epic-01-forum-types-forums/research.md §3.3`.

## Decision drivers
- Covers the published rules found (HSBC, Barclays, Exim, JPMorgan, Catalyst, the Charity Commission, the UK model articles, Robert's Rules).
- Can be written by a company secretary without learning a language, and read back as a sentence.
- Deterministic, side-effect free and testable: the same rule and attendance always give the same answer, for the live view and for the recorded snapshot.
- No second expression language next to CEL (ADR-0039) unless the rules need one.

## Considered options
1. **A structured rule: a fixed vocabulary of conditions, all of which must hold** (chosen).
2. CEL over a fixed set of counts (`present.voting`, `inOffice`, `present.attr.independent`, ...). Expressive, but administrators would write formulas, and errors would be text-level.
3. Fixed presets only (majority, two-thirds, fixed number). Simple, but cannot say "including the chair" or "a majority of independents".

## Decision outcome
Chosen option 1.

### The rule
```json
{
  "counts": "VOTING",              // VOTING (default) or ALL: which members count, once STANDING_INVITEE and OBSERVER are left out
  "basis": "IN_OFFICE",            // IN_OFFICE: counting members in office that day; SEATS: a fixed number of seats
  "seats": null,                   // 1–100, only with SEATS
  "threshold": { "kind": "MAJORITY" }, // MAJORITY | FRACTION {numerator, denominator} | COUNT {count}
  "minimum": 2,                    // floor, 1–100, default 2
  "required": [ ["CHAIR", "VICE_CHAIR"] ], // up to 3 role sets; at least one member of each set present
  "composition": [ { "attribute": "independent", "atLeast": 2 } ], // up to 3; atLeast k, or "majority": true of those counted present
  "alternatesCount": true,         // an alternate present for an absent member counts as that member
  "remoteCounts": true,            // remote attendance counts
  "recusedCount": false            // for an item: members recused from it count (Exim) or not (UK model articles, default)
}
```
- **Needed number:**
  - n is the number of counting members in office, or `seats`.
  - MAJORITY gives `floor(n / 2) + 1`. FRACTION gives `ceil(n × numerator / denominator)`, where the fraction is between 1/10 and 1/1. COUNT gives `count`.
  - The result is the larger of that value and `minimum`.
- **Present** means a counting member who attends, plus the alternates counted for absent members. Remote attendees count only when `remoteCounts` is set. For an item, recused members count only when `recusedCount` is set.
- **Quorate** when the number present is at least the needed number, every required role set has someone present, and every composition condition holds over those counted present.
- *(Amended by ADR-0064, Phase 4 E05: a seat present by its alternate counts towards the needed number only; it never satisfies a required role set or a composition condition, a person counts for at most one seat, and a recused member's alternate does not stand in for that item.)*
- `required` names roles. Attributes come from the forum type's member attributes, and a rule naming an unknown attribute is refused at save (`UNKNOWN_ATTRIBUTE`).

### Evaluation
- `Quorum.evaluate(rule, roster, attendance, item?)` is a pure Kotlin function in the forum module's published API.
  - Inputs: the members in office with role, voting and attributes; who attends and how; recusals for an item.
  - Output: the needed number, the present number, quorate or not, and the list of unmet conditions, each with its numbers.
  - E05 uses it live and stores its input and output with each decision. E01 uses it for the preview.
- **Plain-language echo:** the API returns the rule's parts, and the web renders one sentence from ICU messages, for example "4 of 7 voting members, including the chair or a vice-chair, at least 2 of them independent". A worked example uses the forum's (or a sample) roster, and a tester lets an administrator tick sample attendance.
- Losing quorum mid-meeting, and overriding a missing quorum with a reason (off by default), are E05's.

### Consequences
- Good: covers every rule found; nothing to learn; one evaluator for preview, live and record; easy property tests.
- Bad: a rule outside the vocabulary (for example stepped tables like the Charity Commission's 3 of 4–5, 4 of 6–7) is approximated (MAJORITY gives the same numbers there) or needs a new building block in code. That is acceptable: new blocks are additive and reviewed.
- Follow-ups: E05 adds the live evaluation, snapshots and overrides; E04 adds per-item recusal inputs.

### Confirmation
- Property tests: monotonic (adding an attendee never breaks quorum, but for a composition majority: it is of those counted present, so an attendee without the attribute can break it), threshold arithmetic against a reference, presets against the cited charters' examples.
- PIT on `Quorum`.
- Jazzer on the rule's JSON reading (nightly).

## Pros and cons of the options
- **Option 1:** + friendly, safe, complete for the evidence; − closed vocabulary.
- **Option 2:** + open-ended; − formulas for secretaries, harder to echo as a sentence, a second language to secure.
- **Option 3:** + simplest; − cannot express required roles or composition, which most bank charters use.
