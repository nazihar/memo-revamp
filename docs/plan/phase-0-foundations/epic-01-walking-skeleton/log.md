# E01 Walking skeleton — log

## 2026-10-14
- Plan written (research, ADR-0001…0005, design, threat model, tasks, test plan). The approval gate was delegated by the owner (see `overview.md §10`).
- Implemented T1–T9 on branch `phase-0/e01-walking-skeleton` (local commits; not pushed).
- Verified:
  - `./gradlew build`: 34 backend tests green, including Testcontainers PostgreSQL with the real role script and SeaweedFS with the real bootstrap; ktlint and detekt clean.
  - web: typecheck, 3 Vitest tests, and the build pass on Node 24.21.0.
  - `pnpm dev` (full profile): all five components UP through the Vite proxy; DEGRADED (200) when ClamAV is stopped.
  - ONLYOFFICE has no egress.
  - The S3 app identity cannot change object lock or create or delete buckets.
- Problems hit:
  - VS Code's Java tooling (jdtls Gradle build server, Gradle 9.7.1) built `build-logic` concurrently with the CLI and corrupted outputs. Mitigations: a workspace `.vscode/settings.json` (git-ignored) plus the optional `MEMO_BUILD_ROOT` build-output isolation.
  - Stale precompiled-script accessors left over from the clashing builds needed a clean rebuild.
  - Jackson writes CRLF on Windows; the contract render now normalizes to LF.
  - Hikari `connection-timeout` binds as milliseconds, not as a Duration.
  - MSW 3 renamed `onUnhandledRequest` to `onUnhandledFrame`.
  - TS 6 deprecates `baseUrl`.
  - `exactOptionalPropertyTypes` is incompatible with the generated client.
- Not yet run: the GitHub Actions workflow. The branch is not pushed; pushing needs the owner's go-ahead.
