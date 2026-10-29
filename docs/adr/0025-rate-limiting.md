---
status: accepted
date: 2026-10-18
deciders: product owner (delegated), agent
---

# 0025 — Rate limiting with Bucket4j on PostgreSQL

## Context and problem statement
AGENTS §3.4 requires rate limiting on login, OTP verification, password reset, upload, search and export; AGENTS §2 names Bucket4j with a PostgreSQL-backed store ("verify") and forbids Redis without an ADR. Research: `epic-03-identity/research.md §R11`.

## Decision drivers
- Limits hold across all nodes; no extra infrastructure.
- No personal data in the limiter's storage.
- Small, explicit set of limited endpoints.

## Considered options
1. **Bucket4j 8.21 PostgreSQL extension (`SELECT … FOR UPDATE` proxy manager)**
2. Per-node in-memory buckets (Caffeine): limits multiply with node count
3. Our own counter table

## Decision outcome
Chosen option: **1**.
- Table `identity_rate_limit_bucket(id bigint primary key, state bytea, expires_at bigint)`, a platform table allow-listed in the RLS meta-test.
- Keys: first 8 bytes of SHA-256 of `<limit name>:<subject>` (subject = client IP, tenant + email, or user id), so no IP or email is stored.
- `RateLimiter.consume(limit, subject)` throws `RateLimitedException` → 429 Problem Details with `Retry-After`.
- Initial limits (configurable): login per IP 20/min; login per tenant+email 10/15 min; OTP per user 5/min; password reset per IP 5/h and per tenant+email 3/h; invitation acceptance per IP 10/h. Upload (E05) and search (E04) reuse the mechanism.
- Expired buckets are removed by a daily db-scheduler platform task.
- Client IP: `remoteAddr`, or the forwarded address only from trusted proxies (`server.forward-headers-strategy=native`, Tomcat `internalProxies`).

### Consequences
- Good: cluster-wide limits on the database we already run.
- Bad: one short database transaction per limited request (acceptable for these endpoints).

### Confirmation
- `RateLimiterIT` (limits across two app contexts, `Retry-After`), `LoginRateLimitIT`; RLS meta-test allow-list entry.
