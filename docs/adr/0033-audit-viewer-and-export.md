---
status: accepted
date: 2026-11-08
deciders: product owner (delegated), agent
---

# 0033 — Audit query API, viewer and verifiable export

## Context and problem statement
E02 built the audit store: a per-tenant SHA-256 hash chain written in the business transaction, verification jobs and anchors in the object-locked records bucket (ADR-0019). Tenant admins and auditors now need to search it, read it and export it in a form an outside auditor can verify without trusting memo (overview E07). Research: `phase-1a-minimum-core/epic-07-tenant-admin/research.md §R20`.

## Decision drivers
- Verifiable by a third party with standard tools.
- Streamed: an export of a year must not load into memory or spool to disk.
- Reading the audit is itself privileged and recorded.

## Considered options
1. CSV export of selected columns.
2. **ZIP with the canonical events, their hashes and a manifest.**
3. A signed export (manifest signed with a platform key).

## Decision outcome
Chosen option: **2**, with signing as a follow-up once 1b's key provider can hold a signing key (option 3 builds on it).

- **Query API:** `GET /api/v1/admin/audit` with `from`, `to` (default: last 7 days; at most 1 year), `action` (prefix), `actorId`, `objectType`, `objectId`, `cursor` (keyset on `seq` descending), `limit` ≤ 200. Response rows: seq, time, action, actor type and id (with display name when the actor is a user of this tenant), object type and id, before/after, client address and user agent (the object history views of E04 keep leaving the last two out). Permission `audit.read` (privileged; tenant admins and auditors).
- **Export:** `POST /api/v1/admin/audit/export` with the same filters except paging; step-up required; 5 per hour per tenant. Response: `application/zip`, streamed:
  - `events.ndjson`: one line per event in `seq` order: `{"seq", "prevHash", "hash", "canonical"}` (hashes hex, `canonical` the exact stored canonical text).
  - `manifest.json`: tenant id, chain genesis, filters, first and last `seq`, count, `prevHash` of the first event, `hash` of the last, `sha256` of `events.ndjson`, the anchors whose `seq` lies in the range (seq, hash, object key), exporter (user id), time, memo version.
  - `VERIFY.md`: the algorithm: for each line, `hash = SHA-256(prevHash || UTF-8(canonical))`, `prevHash` equals the previous line's `hash`; the anchors' hashes must match the lines with the same `seq`; the anchors themselves are read from the records bucket by the auditor.
  - Filtered exports (action, actor, object) include only matching events and are **not** chain-continuous; the manifest says `continuous: false` and each line still carries its `prevHash`, so each event's hash can be checked but gaps cannot be proved absent. Only an unfiltered time-range export is `continuous: true`.
- **Audit of reading:** an export records `audit.exported` (filters, count, range) in the same chain. Queries are not audited (they would flood the chain); they are rate limited.
- **Platform chain:** operators get the same query API over the platform chain at `GET /api/v1/platform/audit` (no export in 1a).

### Consequences
- Good: an auditor can verify integrity and continuity with a 20-line script; the anchors prove the hashes existed at anchoring time.
- Bad: without signing, the export proves integrity against the anchors, not authorship; someone with database write access can rewrite the last ≤ 15 minutes before an anchor (ADR-0019).
- Follow-ups: signed manifests with the 1b key provider; scheduled exports to customer storage (Phase 7).

### Confirmation
- `AuditExportIT`: an independent verifier in the test (not memo's `AuditChainVerifier`) recomputes every hash of an export and matches the manifest; an altered line fails; a filtered export says `continuous: false`.
- `AuditQueryApiIT`: filters, keyset paging, other tenants' events never appear; matrix rows for `audit.read`.

## Pros and cons of the options
### CSV
- Good, because spreadsheets open it.
- Bad, because it cannot be verified: the canonical text and hashes are lost.

### ZIP with canonical events and manifest (chosen)
- Good, because it is verifiable and streamable with standard tools.
- Bad, because it is not human-friendly; the viewer covers reading.

### Signed export
- Good, because it proves which system produced it.
- Bad, because it needs a signing key under the key provider, which arrives in 1b.

## More information
- ADR-0019 (audit store).
- https://csrc.nist.gov/pubs/sp/800/92/final (accessed 2026-11-08)
