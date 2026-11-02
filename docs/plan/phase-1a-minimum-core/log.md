# Phase 1a — log

History moved out of `STATUS.md`, newest first.

## 2026-11-02
- PR #21 (fixes from the E03 deploy and the first nightly) and PR #22 (E04) merged. PR #21 deployed to dev; Mailpit runs there again.
- Master CI failed on the PR #22 merge commit, and on a re-run: the test JVM ran out of heap (Gradle's default 512 MiB) in ArchUnit's class import, after the suite had grown with E04. The same tree had passed on the PR, so the limit was being reached only on some runs. The deploy was skipped, so E04 is not on dev yet. The test heap is now 1 GiB.
- Lesson: a green PR run does not prove master will be green when a resource limit is close. Watch the master run before calling an epic deployed.

- PR #23 (test heap, docs) merged: master CI green, and E04 deployed to dev (`efc05e6`) with the Demo Bank: 33 units, 56 positions, 46 people in seats.
- Nightly on `efc05e6`: Firefox and WebKit on the real stack, pseudo-locale, visual, Lighthouse, Helm on kind and the dependency re-scan all pass; the on-prem ZAP scan shows informational alerts only. The ZAP and Gatling job failed before reaching dev: `ci/zap-baseline.sh` reused the first scan's report folder, whose files belong to the container user. Each target now has its own folder.
- A local ZAP scan of dev found one warning the on-prem scan cannot show, because rule 10015 applies only over HTTPS: `Cache-Control: no-store` is already the strictest value but the rule looks for all three directives. The SPA shell now sends `no-store, no-cache, must-revalidate` rather than ignoring the rule, so a page with a real gap would still be caught.

## 2026-10-29
- PR #20 (E03) merged. The deploy left dev at 502 and the first nightly since E03 failed; both are written up in the E03 log. Fixed in PR #21.
- The owner made the repository public for a while so Actions can run (billing block on the private repository).
- E04 plan written and implementation started.

## 2026-10-18
- Wrote the phase overview (`overview.md`): seven epics (E01 app frame and visual design, E02 platform kernel, E03 identity and sessions, E04 org and authorization, E05 files, E06 notifications, E07 tenant lifecycle and administration). It is waiting for owner approval and answers to Q1–Q8.
- The owner approved the overview and kept the per-epic gate delegation. Answers: Q2 host name per tenant (option A), Q3 one account per tenant, Q4 two neutral visual directions proposed by the agent, Q5 MFA required by default (relaxable for non-admins only), Q6 separate admin host with the owner as sole operator, Q7 current VM, Q8 English only.
- PR #17 merged. E02 plan written (ADR-0017 to ADR-0020). E01 round 1: two visual directions sent to the owner for review (`epic-01-app-frame/ux-review.md`). E02 implementation started on `feat/1a-e02-platform-kernel`.
- Owner chose **Direction A (Ledger)** for the app frame (E01 ux-review round 1).
