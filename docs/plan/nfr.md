# Non-functional requirements (NFRs)

> **Status: CONFIRMED** by the product owner on 2026-10-15 (Phase 0 Q6). The targets below come from MASTER_PLAN §6 and TESTING_STRATEGY §7, refined by what Phase 0 measured. Any later deviation needs an ADR. Confirmation is recorded in the table at the end.

## Performance

| # | Target | Measured in Phase 0 | How verified |
|---|---|---|---|
| P1 | p95 < 300 ms for common reads (list/detail API) at the target load | `/api/v1/health` through nginx: p95 48 ms, 20 users (Gatling smoke, local on-prem stack, 2026-10-14) | Gatling per critical journey; nightly smoke, weekly load (TESTING_STRATEGY §7) |
| P2 | First page of a board book visible < 2 s | — (Phase 5) | Gatling + Lighthouse |
| P3 | 500-page book compile < 60 s; 1,500-page book within budget (Phase 5 test) | — | Background-job timing |
| P4 | 1,000 concurrent users per mid-size tenant | — | Weekly load test |
| P5 | Web: LCP ≤ 2.5 s, CLS ≤ 0.1, first-route JS ≤ 250 KB gzip | first-route JS 157 KB gzip (E03 build) | Lighthouse CI (nightly) |
| P6 | Release regression gate: p95 of key endpoints does not regress by more than 10 % | — | Release stage |

## Availability and recovery

| # | Target | Notes |
|---|---|---|
| A1 | SaaS availability 99.9 % monthly | Excludes announced maintenance windows; dedicated and on-prem installs follow the customer's SLA |
| A2 | RPO ≤ 15 minutes | PostgreSQL continuous archiving (WAL) plus object-store replication. Backup design is in Phase 7; a minimal version comes before M1 |
| A3 | RTO ≤ 4 hours | Restore drill before each milestone and quarterly in production (TESTING_STRATEGY §7) |
| A4 | Backups encrypted; restore-tested quarterly | Audit-chain integrity and object-lock state are verified after each restore |

## Synthetic data volumes (performance environments)

| # | Volume | Generator |
|---|---|---|
| V1 | 1,000,000 memos with history per large tenant | `SyntheticVolumeGenerator` (Phase 3 adds memos) |
| V2 | 10,000 active workflow instances with timers | Phase 2 |
| V3 | 5,000 users per tenant | Phase 1b |
| V4 | 1,500-page board books | Phase 5 |
| V5 | 10 years of audit events | Phase 1a |
| V6 | Tenants per SaaS installation: 200, with 1–3 large ones | Phase 0 generator already creates N tenants with settings |

## Security and compliance (cross-reference)
- **Verification level:** ASVS 5.0 L2 overall; L3 for authentication, authorization, cryptography and audit (`docs/security/asvs-checklist.md`).
- **Remediation SLAs:** critical 72 h, high 7 days, medium 30 days (TESTING_STRATEGY §6).

## Capacity of the dev/staging VM (ADR-0015)
- **Minimum:** 8 vCPU, 16 GB RAM, 200 GB SSD to run dev + staging with ONLYOFFICE and ClamAV.
- **Lighter option:** 4 vCPU and 8 GB is enough if ONLYOFFICE and ClamAV run only in staging.

## Confirmation

| Item | By | Date | Notes |
|---|---|---|---|
| NFR targets (performance, availability and recovery, synthetic volumes) | Product owner (aradnazihar) | 2026-10-15 | **Confirmed as proposed.** Approved in the session after the Phase 0 UX walkthrough. |
