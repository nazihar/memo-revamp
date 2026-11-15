---
status: accepted
date: 2026-11-15
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0050 — Memo access: participants, copy-to, shares and unit readers, under clearance, as one SQL filter

## Context and problem statement
Today a memo is readable by its participants, meaning the initiator, co-authors, approvers and people notified by the workflow, if they are cleared for its classification. To everyone else it does not exist (404). The master plan asks for more ways in:
- explicit shares;
- "copy to" recipients, with read receipts;
- people who read for a unit, for example a branch manager or an internal auditor over a division.

Every list, search and dashboard must apply the same rule in SQL, so nothing the user cannot see is ever loaded (overview, "Access model"; ADR-0027 extended). How is the rule expressed and stored, and what are copy-to and a share?

## Decision drivers
- Deny by default; one rule everywhere; fast enough for lists and search at 1,000,000 memos (E05).
- Classification clearance always applies; a way in never bypasses it.
- Grants are visible, attributable, revocable and audited.
- Banks distribute memos to people by post (position) as well as by name.

## Considered options
1. **One reader table** (`memo_participant`, extended with new kinds) plus a unit-scope predicate, under the existing classification check.
2. Separate tables per way in, combined with `UNION`/`OR EXISTS` at query time.
3. A materialised access list rebuilt by a job.

## Decision outcome
**Option 1.**

- **Readers.** A user may read a memo when the memo's classification rank is at most their clearance, and **either** of these holds:
  - they have a row in `memo_participant`. The kinds are `INITIATOR`, `COAUTHOR`, `APPROVER` and `NOTIFIED` (as today), plus `COPY` (a copy-to recipient) and `SHARED` (an explicit share);
  - or they hold the new permission `memo.read.unit` with a scope that covers the memo's `origin_unit_id`. It is a scopable permission (ADR-0027), granted through a role tenant-wide or for a unit subtree, and is not in the default user role.

  One SQL predicate (`MemoReadable`) is used by get, lists, inbox, search and dashboards. Revoked rows are kept with `revoked_at` and do not count.
- **Shares:**
  - Given by a participant who may read the memo, to one person, with a **reason** (required) and an optional **expiry**.
  - The recipient must be cleared for the memo; a share never lifts classification.
  - Revocable by whoever gave it, by the initiator, and by holders of `memo.share.manage`.
  - Stored in `memo_share` (who, by whom, reason, expiry, revoked), which writes or retires the participant row. An expired share stops counting at once (the predicate checks `expires_at`), and a job retires it.
- **Copy-to:**
  - **Recipients:** people, positions (resolved to their holders when the copy is sent, and recorded per person), or a **distribution list**. Lists are named sets of people and positions kept by tenant admins (`memo.distribution.manage`).
  - **When:** each recipient is sent either **at submit**, for information during review, or **after final approval**.
  - **Who sets it:** the initiator or a co-author while the memo is a draft or returned. After approval, holders of `memo.copy.add` may add recipients, for example a registry clerk. Nothing is removed once sent.
  - **Sending** makes each recipient a `COPY` participant, if cleared, and notifies them. Recipients not cleared for the memo are refused at submit, with the list of names, like the route check.
  - Copy recipients **read only**: no actions and no remarks, but they may write a comment if the tenant setting `memo.copy.comments` is on (off by default).
- **Read receipts:**
  - A receipt records the first time each copy recipient (and each approver) opens the memo view, with the time and the document version shown. It is written once per person and memo, then never changed.
  - The sender, the initiator and co-authors see a list of who has read and who has not.
  - Opening the document or an attachment is audited as today (`file.opened`). The receipt is about the memo, not each file.
- **Who may see who has access:** participants see the lists of participants, copy recipients and shares; unit readers do not appear on them. Every grant, revoke and expiry is audited.

### Consequences
- Good: one indexed `EXISTS` on `memo_participant (tenant_id, user_id, memo_id)` plus a unit predicate. Lists and search stay a single query.
- Good: a share or copy shows who gave it and why; nothing is implicit.
- Bad: position recipients are resolved when sent. A later holder of the post does not get earlier copies automatically; they can be shared.
- Bad: unit readers widen access; the permission is privileged and its grants are audited.

### Confirmation
- An authorisation matrix per endpoint for every kind of reader, including revoked, expired, under-cleared and other-tenant users.
- A property test: for random memos, grants and clearances, the SQL predicate equals a reference implementation in Kotlin.
- Performance: the memo list p95 under 100 ms with 100,000 memos and 20,000 shares and copies (design §9).

## Pros and cons of the options
### Option 1 — one reader table plus unit predicate
- Good: simple, one index, already used by every query.
- Bad: share and copy metadata live in their own tables beside the participant row (written in the same transaction).
### Option 2 — union at query time
- Bad: several `EXISTS` per row in every list; easy to forget one in a new query.
### Option 3 — materialised access list
- Bad: staleness after a revoke; a job on the critical path of security.

## More information
ADR-0027 (authorisation model), ADR-0046 (attachment classification), research §B.4 (D-Nothi, e-Office practice), design E03 §4–§5.
