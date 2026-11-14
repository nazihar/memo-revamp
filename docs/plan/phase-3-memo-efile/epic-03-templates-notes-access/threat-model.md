# E03 Templates, note sheet and access — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows, with mitigations mapped to OWASP ASVS 5.0 requirement areas. Builds on the E02 threat model (files, conversion, attachments).

## Data flows
1. Admin → files API → template sanitiser → stored template → fill at create → memo document v1 → ONLYOFFICE (editing, conversion).
2. Reader → note editor → `POST /notes` (JSON) → validator → `memo_note` → React renderer; and (E04) the PDF renderer.
3. Reader → share or copy-to → `memo_participant` → the `MemoReadable` predicate in every list and get.
4. Approver → step-up → decision → `memo_approval` (authentication context, hashes) → stamp → check.
5. Workflow end → copy sending job → notifications.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| T1 | **Tampering/elevation:** a template with macros, ActiveX, OLE, `altChunk`, external relationships, `attachedTemplate`, or `INCLUDE`/`LINK`/`DDE` fields reaches ONLYOFFICE or an initiator | 1 | Refused at upload, not cleaned silently; the re-serialised package is stored; the files pipeline scans it (ADR-0029); ONLYOFFICE's external requests are off (ADR-0047) | V12 (files), V14 |
| T2 | **Elevation:** expression injection through template tags (SpEL) | 1 | Default poi-tl grammar only; an ArchUnit ban on SpEL; data is strings only | V1 (injection), V15 |
| T3 | **Denial of service:** a zip bomb or huge template; a slow fill | 1 | 10 MiB limit; `ZipSecureFile` limits; a bounded executor (2 per node) with a 5 s timeout | V12, V15 |
| T4 | **Tampering:** a tag value carrying markup or tags (`{{…}}`, XML) | 1 | Values inserted as text runs by the engine; tests with hostile values | V1 |
| T5 | **XSS** through a note (links, mentions, pasted HTML) | 2 | JSON schema allow-list, rejecting unknowns; `https:` only for links; React rendering without raw HTML; `rel="noopener noreferrer nofollow"`; strict CSP unchanged; an E2E test fails on any CSP violation | V3 (web front end), V1 |
| T6 | **DoS:** a deep or huge note | 2 | 64 KiB, 2,000 nodes, depth 8, Jackson stream constraints on the endpoint | V15 |
| T7 | **Information disclosure:** a mention reveals the memo to someone without access, or a `memoRef` leaks another memo's subject | 2 | A mention requires read access (refused otherwise, share first); a `memoRef` stores only the id and a reference snapshot, rendered "Memo (restricted)" to readers without access | V8 (authorisation) |
| T8 | **Repudiation/tampering:** a note or approval altered or removed | 2, 4 | Append-only tables (grants and triggers); corrections by reference; an approval hash chain and Check; audit events | V16 (logging), V8 |
| T9 | **Elevation:** a list or search forgets the access rule | 3 | One `MemoReadable` predicate; an ArchUnit rule against direct participant joins; a property test comparing SQL with a reference; matrix tests per reader kind | V8 |
| T10 | **Elevation:** a share or copy lifts classification | 3 | Clearance always ANDed; recipients not cleared are refused (share) or not sent (copy) and listed | V8 |
| T11 | **Elevation:** a stale share after expiry or revoke | 3 | The predicate checks `revoked_at` and `expires_at` directly; the sweep is cosmetic | V8 |
| T12 | **Information disclosure:** stamp client data (IP, device) exposed | 4 | Shown to `audit.read` holders only; session ids stored as an HMAC | V8, V14 (data protection) |
| T13 | **Spoofing:** a decision without the right person present | 4 | Step-up on approve (ADR-0036) with its age recorded; the stamp shows it | V6 (authentication) |
| T14 | **Repudiation:** "I never received the copy" / "I never opened it" | 5, 3 | Delivery rows per person; receipts on first open with the version; notifications logged | V16 |
| T15 | **DoS/spam:** mentions or copies used to spam many people | 2, 5 | ≤ 20 mentions per note; copy lists limited to 500 resolved people; rate limits on posting notes (30/min per user) and on Check (10/min) | V15 |
| T16 | **Information disclosure:** a remark draft visible to others | 2 | Drafts readable only by their owner (RLS plus an owner check); not returned in any list | V8 |

## Residual risks
- A database owner could rewrite approval rows and their chain together. E04's sealed, object-locked PDF/A is the external anchor (ADR-0051).
- poi-tl maintenance (one maintainer). We wrap it and keep a fallback with the same syntax (ADR-0048).
- Unit readers widen access by design. The permission is privileged, its grants are audited, and it is not in any default role.
