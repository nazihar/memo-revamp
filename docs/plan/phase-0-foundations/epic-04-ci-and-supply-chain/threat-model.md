# E04 CI pipeline and supply chain — threat model (STRIDE)

## Data flows
1. Developer → GitHub (push, PR).
2. GitHub Actions runner → package registries (Maven Central, npm, Docker Hub, gcr.io, mcr), vulnerability DB.
3. Release job → GHCR (images), signatures and attestations.
4. Customer → GHCR or an offline OCI archive → `cosign verify`.

| # | Threat | Mitigation | ASVS 5.0 / SLSA | Test / evidence |
|---|---|---|---|---|
| T1 | A compromised dependency or transitive version drift | Lockfiles for Gradle and pnpm; Renovate PRs pass every gate; Trivy SBOM scan | V15.2.x; SLSA source/build | Lockfile enforcement; `security` job |
| T2 | A compromised GitHub Action (tag moved) | Actions pinned by commit SHA; minimal `permissions` | SLSA build | actionlint + review; Scorecard "Pinned-Dependencies" |
| T3 | A tampered image between registry and customer | cosign key signature on the digest; SBOM and provenance attestations; offline verification | V15.1.x; SLSA L2 | `cosign verify` step in `release.yml` |
| S1 | Release from an unreviewed commit or fork | Release only on protected tags; `release` environment requires owner approval; no secrets for fork PRs | SLSA source | Repository settings (owner), workflow conditions |
| I1 | Secret committed | gitleaks pre-commit + CI (full history on master) | V13.3.1 | `security` job; planted-secret fixture test |
| I2 | A licence violation ships to on-prem customers (AGPL/GPL linked) | Licence allow-list over SBOMs; AGPL/GPL only as separate services, recorded in the register | AGENTS §3.1 | `license-check.mjs` fails on a planted GPL component |
| E1 | Container escape or privilege inside the image | non-root uid, no shell (backend), read-only root FS, minimal base, Trivy image scan | V15.2.x | `images` job; `docker run --read-only` smoke |
| D1 | Vulnerability DB or registry outage blocks builds | Cached DB on runners; air-gapped mirror documented | — | `ci/README.md` |

## Residual risks
- The cosign private key is held as a GitHub secret until it moves to KMS/HSM in Phase 7.
- GitHub-hosted runners are shared infrastructure. Self-hosted, isolated runners are an option for the pilot (ADR-0011).
