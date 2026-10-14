# STATUS

> Read at the start of every session; update at the end (see `AGENTS.md §0`). Keep this page short and current. Move history into the phase folder's `log.md`.

**Last updated:** 2026-10-14 (UTC+06:00) by Claude (agent)

## Current position
- **Phase:** 0 — Foundations and engineering baseline (overview approved 2026-10-14; the per-epic review gate is delegated by the owner, see `phase-0-foundations/overview.md §10`)
- **Epic:** E03 — Web foundation (next)
- **Branch / PR:** E01 → `phase-0/e01-walking-skeleton`; E02 → `phase-0/e02-data-and-tenancy` (stacked on E01). **Local commits only, nothing pushed yet.**

## State
- [x] Phase overview approved; Q1, Q2, Q4, Q5 and Q7 answered
- [x] E01 Walking skeleton: planned, implemented, verified locally (`epic-01-walking-skeleton/log.md`)
- [x] E02 Data and tenancy: planned, implemented, verified locally (66 backend tests) (`epic-02-data-and-tenancy/log.md`)
- [ ] E03 Web foundation
- [ ] E04 CI and supply chain (GitHub Actions)
- [ ] E05 Environments and deploy (owner's own server over SSH)

## Next step
- E03 plan and implementation: shadcn/ui shell, tokens, light/dark, react-intl (ICU, RTL, pseudo-locale), ESLint + Prettier, Playwright + axe, CSP build.

## Blockers
- None for E03 or E04 work.
- E05 needs the server details (see below).
- CI has never run: the branches have not been pushed.

## Waiting for human
- Permission to **push** the branches to `github.com/nazihar/memo-revamp` and open PRs. Needed to run CI.
- E05: the dev and staging server (host, OS, SSH access for CI, DNS/TLS).
- Q3 (team and timeline) and Q6 (who confirms the NFRs) are still open.
- Assumption A1: the base package `com.memorevamp` follows the repository name. Rename before Phase 1a if the product gets a final name.

## Decisions taken
- ADR-0001 to ADR-0008 (`docs/adr/README.md`).

## Environment notes (dev machine)
- VS Code's Java extension builds this tree with its own Gradle 9.7.1. CLI builds use `MEMO_BUILD_ROOT=<repo>/.build-cli` to avoid output corruption (see README).

## Milestone tracker
| Milestone | Target | Status |
|---|---|---|
| D1 — Pilot demo: simple sequential memo approval | end of Phase 2 slice 1 | not started |
| M1 — Pilot memo go-live (incl. pen test) | end of Phase 3 | not started |
| M2 — Committee meetings live | end of Phase 4 | not started |
| M3 — Board-grade web | end of Phase 5 | not started |
| M4a — Mobile approver app | end of Phase 6a | not started |
| M4b — Mobile director app | end of Phase 6b | not started |
| M5 — Enterprise-ready | end of Phase 7 | not started |
