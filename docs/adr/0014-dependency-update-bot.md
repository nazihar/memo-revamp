---
status: accepted
date: 2026-10-14
deciders: product owner (delegated; app installation pending), agent
---

# 0014 — Dependency update bot: Renovate

## Context and problem statement
AGENTS §4 requires automated update PRs that pass every gate, with security updates merged within the SLA (critical 72 h, high 7 days). Research: `epic-04-ci-and-supply-chain/research.md §R16`.

## Considered options
1. Renovate
2. Dependabot

## Decision outcome
Chosen option: **Renovate**, configured in `renovate.json`. It handles the Gradle version catalog, Gradle lockfile maintenance, pnpm, Docker digests and GitHub Actions SHAs. It is also open source and self-hostable, so it can follow us to an on-prem Git server.

Policy:
- Grouped weekly PRs by ecosystem (Spring, Kotlin, TanStack, test tools, lint tools, CI actions, images).
- Security updates run immediately, outside the schedule.
- Majors are separate, unscheduled PRs that need review.
- The pre-1.0 `@hey-api/openapi-ts` is never grouped and always gets a manual review (ADR-0002).
- Lockfile maintenance runs monthly.
- Automerge is **off** in Phase 0. Every PR needs green CI and a human merge.

**Owner action:** install the Renovate GitHub App on `nazihar/memo-revamp`. Until then no update PRs are created, and nothing else is affected.

### Consequences
- Bad: a third-party dev-time service (the Renovate app) gets access to the repository. It is not a runtime dependency, so AGENTS §3.1 is not affected. The self-hosted fallback is `renovatebot/github-action` with a fine-grained token.
