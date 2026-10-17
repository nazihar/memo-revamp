# Phase 0 — log

History moved out of `STATUS.md`, newest first.

## 2026-10-13
- Wrote the phase overview (`overview.md`). It is waiting for human approval and answers to Q1–Q7.

## 2026-10-14
- The owner approved the overview and delegated the per-epic gate. Answers: Q1 GitHub Actions, Q2 own server, Q4/Q5/Q7 accepted.
- E01 done: skeleton, local stack, health slice, minimal CI, docs scaffolding.
- E02 done: roles, RLS conventions, tenant helper, jOOQ codegen, meta and negative tests, demo tenant.
- The VS Code Java extension (Gradle 9.7.1) clashed with CLI builds. Mitigated with workspace settings and the `MEMO_BUILD_ROOT` isolation.
- E03 done: shell, tokens, themes, react-intl with RTL and pseudo-locales, ESLint + Prettier, Playwright + axe, CSP.
- E04 done:
  - every CI gate scripted and run locally;
  - Trivy found CRITICAL Tomcat and HIGH Jackson CVEs, fixed with security overrides;
  - signed-release workflow ready (needs the owner's cosign key).
- E05 done:
  - operability baseline and migration job;
  - on-prem Compose profile, Helm chart, Ansible playbook;
  - deploy with rollback;
  - ZAP and Gatling.
  - Blocked on the owner for push, the VM and secrets.
- Phase exit pending: GitHub CI runs, dev deployment, UX walkthrough, NFR confirmation (see STATUS).

## 2026-10-15
- Product renamed to `memo` (PR #6). Phase 0 exited with the owner's approval (PR #7).
- Release signing key added (PR #8). The first `v0.1.0` release runs failed on cosign 3 (transparency log, then digest parsing); fixed in PRs #9 and #10. `v0.1.0` published with signed images.

## 2026-10-16/17
- The first nightly failed only on the lockfile re-scan: new HIGH Jackson CVEs in `build-logic/gradle.lockfile`, pulled in by the Gradle plugins. The app was already on the patched versions. PR #11 applies the same security BOMs to build-logic; a manual nightly re-run passed.
