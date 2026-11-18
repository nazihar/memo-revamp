---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar (reported: one change from the overview, the recused member's minutes)
---

# 0062 — Conflicts of interest: per-item declarations and one recusal rule

## Context and problem statement
A committee member with an interest in an agenda item must disclose it, and in a bank must take no part in that item: no papers, no discussion, no decision. Phase 4 Q6 settled that declarations are made per item, with a prompt when the agenda is published, and that a standing register waits for a customer with the data. ADR-0061 left a named hook in the one paper-access rule (`PaperExclusions`) for E04. ADR-0057 already lets a forum type say whether recused members count for an item's quorum (`recusedCount`). What remains is what a declaration is, who may make and rule on it, what it does, and how every surface is held to it. Research: `phase-4-forums-meetings/epic-04-conflicts-recusal/research.md §3–§5`.

## Decision drivers
- Supervisors and the law: prompt disclosure of the specific matter (Basel 2015 ¶83, EBA/GL/2021/05 ¶113, Companies Act 2006 s.177), no involvement in the decision (EBA ¶115(c), ¶125), measures documented and decided (EBA ¶117, ¶119), and minutes that record the declaration, the decision on attendance and that the member took no part (CGI *Minute taking* §5.3.6).
- Deny by default and fail closed (AGENTS §3.4): papers must not reach a conflicted member while anything is pending.
- One rule, enforced server-side in the SQL every read path shares, proved by a leak test per surface (overview E04, risk R1).
- Usable by older, non-technical directors: one question per item, the safe choice as the default, nothing to re-declare.
- The record is append-only (AGENTS §3.3).

## Considered options
1. **A declaration is a record only;** access is restricted by hand per paper (eScribe, Diligent, OnBoard, Convene).
2. **A declaration means abstain only;** the member keeps papers and discussion (the narrowest reading of Basel ¶83).
3. **A declaration means leave the room,** with a chair's ruling for a member who asks to take part, applied through one rule (chosen).

## Decision outcome
Chosen option 3.

- **Declarations:**
  - Made per agenda item (or sub-item; one on an item covers its sub-items) by the member, by a secretary or the chair on the member's behalf ("by email on …", "at the meeting"), or **carried over** from an interest on the source memo (SOD-4) or from a recusal on an earlier placement of the same submission.
  - Each holds the nature (free text, 1–1,000 characters, never changed), the member's intent ("I will leave the room", the default, or "I ask the chair to decide"), who recorded it and how, and when.
  - **States:** RECUSED; AWAITING_RULING; TAKING_PART (the chair allowed it); WITHDRAWN. Changes are append-only events. A withdrawal or a corrected nature is a new entry, never an edit.
  - **Rulings:** the chair rules (the vice-chair when the chair is the declarant or there is no chair), or the secretary records the chair's ruling on their behalf. "Must leave the room" makes it RECUSED; "may take part" makes it TAKING_PART.
- **The recusal rule:** a person is recused from item *i* when they hold a RECUSED or AWAITING_RULING declaration on *i* or on its parent. AWAITING_RULING counts as recused (fail-closed).
  - It implements `PaperExclusions.excluded` and `excludedFromPapers` (ADR-0061) as one anti-join in `PaperAccess`'s conditions, for everyone, officers included.
  - The recused person keeps the item's number, time and title, so they know when to leave, unless the item hides its title ("Restricted item"). They see their own declaration on the line.
  - Everything else of the item is withheld: description, resolution sought, presenters' papers, discussion requests, change flags and notifications about it.
  - A published `Recusals` API gives the same facts to the surfaces outside `PaperAccess`: the item's quorum (E05, with ADR-0057's `recusedCount`), the decision (E06, recused members are not counted or named as voting), the minutes (E06) and search rows (E08). `DeclarationChanged` keeps them in step.
- **The prompt:** when the agenda is published, and when a revision adds items, each member is asked to review it. One confirmation per meeting ("I have declared my interests", recorded with the revision number) ends the reminders; one reminder goes before the meeting to those who have not confirmed.
- **Officers:** a recused secretary or chair sees only what any recused member sees of that item, cannot change it in the builder or rule on it, and cannot preview it as someone else. Its late-paper and ruling requests go to the vice-chair or the other secretaries.
- **The memo:** a declaration on a submission item also records a memo interest (SOD-4) with a fixed reason and without the nature, so the declarant is never asked to approve the memo later. Recusal does not revoke memo access granted by the memo rule; the secretary sees when a recused member also reads the memo.
- **The recused member's minutes (changes the overview):** the item's heading, their declaration and the decision, without the discussion or papers; with a hidden title, only "Item n — restricted". The overview said "a version without the items". CGI §6.4 found financial-services boards lean to redaction, with a recognised middle view that the director "is entitled to know the decision, but not any detail of the discussion"; a director is collectively responsible for the decision and learns it anyway. E06 builds it.
- **No standing register** in Phase 4 (Q6). `source` on a declaration leaves room for a REGISTER source later.

### Consequences
- Good:
  - The declaration is the access change; no administrator has to remember to restrict papers, and replacements and late papers are covered at once.
  - One rule for every surface, officers included; the leak suite proves it per surface.
  - The minutes, quorum and decision get exactly the facts CGI says to record.
  - Nothing to re-declare for a deferred paper or an interest already on the memo.
- Bad:
  - A member who asks the chair loses the papers until the chair rules. That is deliberate (fail-closed); the chair is notified at once, and the secretary can record a ruling given by phone.
  - A recused secretary or chair needs a colleague for that item. A forum with one secretary relies on `forums.manage` holders; the register warns.
  - A recused member who reads the memo through the memo rule can still read the memo. This is stated to the secretary rather than hidden.
  - "Stays but does not vote" is not offered; it is minuted by hand in Phase 4.
- Follow-ups:
  - E05: per-item quorum and attendance use `Recusals`; whether a recused member's alternate stands in for that item is E05's question.
  - E06: decisions exclude recused members; the minutes template gains the declaration tags; recused members get the redacted minutes above.
  - E07, E08: actions, the decision register, search rows and analytics join the leak suite.
  - A standing register when a customer supplies counterparty data (Q6).

### Confirmation
- `RecusalLeakIT`: for every surface in the registry, a recused member (and a member awaiting a ruling) sees no hidden title, no paper, no item text, no count and no notification of the item; canary strings planted in the item, its papers and their extracted text never appear in any response, email, notification or export.
- Property test: adding a declaration never widens what anyone sees (`PaperAccess` with recusals ⊆ without).
- Matrix tests with a recused-member column (`p4-e04-declarations.yaml`, and the column added to `p4-e03-meetings.yaml`).
- State-machine tests for every allowed and forbidden declaration transition.
- The registry meta-test: a controller method in `meeting` (and later `minutes`, `actions`) without a probe fails the build.

## Pros and cons of the options
- **Option 1:** + simplest; − every protection depends on an administrator remembering, which is what users and supervisors criticise; leaks are likely.
- **Option 2:** + matches the minimum wording of Basel ¶83; − papers and discussion still reach the member, against EBA ¶125, ORIC, the ABA and the Charity Commission.
- **Option 3:** + strict by default, one rule, the record the minutes need; − a pending ruling withholds papers, and officers who are conflicted need a colleague.

## More information
- Basel Committee, *Corporate governance principles for banks* (2015): <https://www.bis.org/publications/201507-guidelines-corporate-governance-principles-banks.pdf> (accessed 2026-11-18).
- EBA/GL/2021/05: <https://www.eba.europa.eu/sites/default/files/document_library/Publications/Guidelines/2021/1016721/Final%20report%20on%20Guidelines%20on%20internal%20governance%20under%20CRD.pdf> (accessed 2026-11-18).
- CGI, *Minute taking* (2017): <https://cgi.org.uk/media/mpjeexxj/minute-taking.pdf> (accessed 2026-11-18).
- ADR-0057 (quorum), ADR-0059 (submissions), ADR-0061 (the paper-access rule and its hook).
