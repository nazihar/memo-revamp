# E01 Forum types and forums — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows; ASVS 5.0 IDs as in `docs/security/asvs-checklist.md` (chapter-level where the checklist groups them).

## Data flows
1. A governance administrator (maker) creates a forum-type draft from a preset, edits it and submits it.
2. A second administrator (checker) approves it with step-up. The published version becomes the policy of the type's forums.
3. A governance administrator creates, edits, clones or dissolves a forum.
4. A governance administrator or the forum's secretary adds, edits, renews, re-roles or ends memberships. The chair and secretaries are notified.
5. Members, former members, auditors (`forum.read`) and administrators read forums and rosters, and "My forums".
6. A workflow author publishes a definition with an `escalate-to-forum` step. The workflow asks the forum module whether the key is an active forum.
7. The scheduler runs term reminders per tenant.
8. Anyone allowed computes a quorum preview from a rule and sample attendance.

## STRIDE

| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | A maker publishes their own policy change by holding both permissions | 2 | `reviewed_by <> submitted_by` CHECK and `SAME_PERSON`; step-up on approve; audit | V8 authorisation, V2 business logic |
| S2 | A stolen session publishes a type | 2 | Step-up (fresh MFA) on approve; the session's IP and device in the audit entry | V6, V7, V16 |
| S3 | A secretary makes themselves chair, adds a second secretary, or extends their own term to keep access | 4 | `SECRETARY_LIMIT`: secretaries never touch CHAIR or SECRETARY rows or their own row; the chair and other secretaries are notified of every change; audit with before and after | V8 (least privilege, separation of duties) |
| T1 | A membership row is rewritten to hide who sat on a committee when a decision was taken | 4 | Role and term changes create new rows, and rows are ended, never deleted (no DELETE grant for the app role on `forum_member`); every in-place edit is audited with before and after; decisions snapshot their roster (E05, E06) | V16 audit, V2 business logic |
| T2 | Two administrators appoint two chairs at the same moment | 4 | Invariants checked under `FOR UPDATE` on the forum row; concurrency test | V2 business logic (concurrency) |
| T3 | Lost update on a forum or member edit | 3, 4 | If-Match on `version`; 412 on mismatch | V2 |
| R1 | Someone denies having appointed a member | 4 | Audit entry with actor, acting-for, IP and authority text; notification to the chair and secretaries | V16 |
| I1 | The existence or roster of a confidential committee (for example a special investigation committee) leaks to staff | 5 | `ForumVisible`: members, former members (name and own history only), `forums.manage`, `forum.read`, all under clearance of the forum's classification; deny by default; 404 for invisible forums (no existence oracle) | V8 |
| I2 | Escalation targets list forums to workflow authors | 6 | Accepted: only holders of `workflow.definitions.manage` (privileged administrators) see keys and names of active forums; documented in the runbook | V8 |
| I3 | Reminder or membership emails reveal the committee in the subject | 7, 4 | Subjects take no parameters (ADR-0030); bodies stay inside the tenant's relay | V14 data protection |
| I4 | A member's position seat resolves to the wrong person after a reorganisation | 5 | Holders read from the org directory on the date asked; roster cache evicted on `OrgChanged`; a vacant seat is a vacancy, never a fallback person | V8 |
| D1 | Huge rules or attribute lists slow previews | 8, 1 | Typed request objects with caps (8 attributes, 3 required sets, 3 composition conditions, 8 outcomes; JSON ≤ 16 KiB); the preview roster is capped at 100 members, and the preview is a pure computation with no query | V2 input validation, V13 |
| D2 | The reminder job floods mail after downtime | 7 | Only `term_ends_on = today + r` is matched, so missed days are skipped, not batched; the reminder table keeps it idempotent | V2 |
| E1 | A `forum.read` grant opens meeting papers later | 5 | `forum.read` covers forum metadata and rosters only; papers (E03) have their own rule (ADR-PAPERS) | V8 |
| E2 | A cross-tenant reference (a forum of tenant A naming a type or user of tenant B) | 3, 4 | Composite foreign keys with `tenant_id`; RLS FORCED; cross-tenant tests | V8 (multi-tenancy) |

## Residual risks
- Membership changes are not four-eyes. This is mitigated by notifications and audit (ADR-0056, option 4 rejected), and can be made a tenant option later.
- A tenant administrator holds every forum permission. That is the built-in role's design (ADR-0027); tenants that want separation grant `forums.manage` to a governance role and keep `tenant_admin` small.
