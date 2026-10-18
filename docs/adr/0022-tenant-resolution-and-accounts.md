---
status: accepted
date: 2026-10-18
deciders: product owner (Phase 1a Q2, Q3), agent
---

# 0022 — Tenant resolution from the host name; one account per tenant

## Context and problem statement
Before a user signs in, the application must know which tenant (organisation) the request belongs to: the login page shows its branding, and credentials are checked against that tenant's accounts. The owner chose host-based tenants and per-tenant accounts (Phase 1a overview Q2, Q3). Research: `phase-1a-minimum-core/epic-03-identity/research.md §R10`.

## Decision drivers
- Strong isolation: an account never spans tenants.
- Works for SaaS (many tenants), dedicated and on-prem (one tenant).
- No DNS-provider credentials on our servers.

## Considered options
1. **Host name per tenant (`<slug>.<base domain>`), single-tenant mode for dedicated/on-prem**
2. Organisation code typed on the login page
3. Global email lookup ("email first")

## Decision outcome
Chosen option: **1**.
- `memo.tenancy.mode`: `multi` (default) resolves the tenant from the request host `<slug>.<memo.tenancy.base-domain>`; `single` always uses `memo.tenancy.single-tenant` (a slug).
- The host is taken from `Host`; forwarded headers are honoured only from trusted proxies.
- Resolution runs in platform scope (ADR-0017) and is cached in-process (TTL plus eviction on tenant changes from E07). Unknown host → 404 Problem Details without revealing whether other tenants exist.
- Every request then runs in that tenant's `TenantContext`; an authenticated session is valid only on the host of its tenant (a session for tenant A presented on tenant B's host is rejected and cleared).
- **Accounts** (`identity_user`) belong to exactly one tenant: email is unique per tenant, not globally. No tenant switcher.
- **TLS:** on our Caddy edge, on-demand certificates for tenant hosts, authorised by an `ask` endpoint on the backend's management port (not reachable from the internet) that returns 200 only for existing tenant hosts.
- Email-first discovery can be added later on top of this model.

### Consequences
- Good: tenant known before login; branded login pages; per-tenant IdP configuration (1b) fits naturally; no global account index.
- Bad: one DNS wildcard and on-demand TLS per environment; people in two tenants have two accounts.

### Confirmation
- `TenantResolutionIT`: host → tenant; unknown host 404; single-tenant mode; session from tenant A rejected on tenant B's host.
- `CaddyAskEndpointIT`: management-port endpoint answers 200/404 and is not exposed on the API port.
