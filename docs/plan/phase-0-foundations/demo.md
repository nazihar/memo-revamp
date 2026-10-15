# Phase 0 demo script

**Audience:** product owner and engineering. **Duration:** about 20 minutes. **Goal:** show that the walking skeleton builds, tests, scans and runs end to end, and that the guard rails work.

## 0. Prerequisites
Docker running, Node 24, and pnpm 10. Run `pnpm install` once.

## 1. One command starts everything (exit criterion 1)
```sh
pnpm dev            # full stack: PostgreSQL, SeaweedFS, ONLYOFFICE, ClamAV, Mailpit + backend + web
```
1. Open http://localhost:5173. The health page shows every component **Up**.
2. Stop ClamAV:
   ```sh
   docker compose -p memo-local stop clamav
   ```
   Within 5 s the page shows **Degraded**, and Malware scanner shows **Down**, as text and an icon, not colour alone.
3. Start it again.

## 2. The shell (E03)
1. Press **Tab**: the skip link appears first. Press **Enter** and focus jumps to the main content.
2. Open the theme menu: Light, Dark, System. The choice persists on reload, with no flash of the wrong theme.
3. Open the language menu and choose **العربية**. The whole layout mirrors (navigation moves to the right) and every string is translated.
4. Dev builds also offer the pseudo-locales `[Ƥşḗḗŭŭḓǿǿ]`: every string is accented and bracketed, so a hard-coded string would stand out.

## 3. Tenant isolation (exit criterion 3)
```sh
./gradlew :backend:test --tests '*CrossTenantIsolationIT' --tests '*RlsNegativeIT' --tests '*RlsMetaTest'
```
- **Cross-tenant:** 10 attack probes (read, list, join, view, function, update, delete, insert with a foreign tenant, no context) all find nothing.
- **Negative test:** with RLS switched off, the same probes find **all 10 leaks**. The tests really test RLS.
- **Meta-test:** a catalog scan fails the build for any future table without the conventions.

## 4. Contract and generated code
1. Change an endpoint without running `./gradlew :backend:updateContract`. `./gradlew build` fails with "API changed without updating the contract".
2. The same happens for the jOOQ code (`updateJooq`) and the web client (CI regenerates it and diffs).

## 5. Supply chain (exit criterion 2)
```sh
./gradlew cyclonedxBom && pnpm sbom && node ci/license-check.mjs --self-test
```
1. Show `build/sbom/licenses.md`.
2. Explain the Trivy gate: it found CRITICAL Tomcat and HIGH Jackson CVEs in the framework defaults, now pinned to patched versions (ADR-0013).
3. Show the Semgrep rule tests: `semgrep --test .semgrep`.

## 6. Production images and the on-prem profile (E04/E05)
```sh
ci/image-smoke.sh memo-backend:local memo-web:local
ci/onprem-smoke.sh memo-backend:local memo-web:local
```
The containers run non-root on a read-only root filesystem with no capabilities.

The migration job runs first with the migrator role; the backend holds only the app role.

## 7. Deploy with rollback (one-command deploy to dev)
`deploy/scripts/deploy.sh dev <backend@digest> <web@digest>` with a deliberately broken backend:
1. the migration job fails;
2. the script restores the previous digests;
3. the environment is healthy again;
4. the failure is written to `deployments.log`.

This is the same script CI runs over SSH (`deploy-dev.yml`) once the VM exists.

## 8. What is still open
- **Push to GitHub:** CI has not run yet.
- **The dev/staging VM:** Ansible playbook and deploy workflow are ready.
- **cosign key and the Renovate app.**
- **NFR confirmation** (`docs/plan/nfr.md`).
- **The owner UX walkthrough** (E03 `ux-review.md`).
