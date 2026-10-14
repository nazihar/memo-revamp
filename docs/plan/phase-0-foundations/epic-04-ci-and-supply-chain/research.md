# E04 CI pipeline and supply chain — research

Sources accessed **2026-10-14**: GitHub Releases API (tool versions), Docker Hub / gcr.io / mcr.microsoft.com (images and digests), npm registry, official tool documentation.

## R13 — CI platform and runner model (Q1 answered: GitHub Actions)
- **GitHub-hosted `ubuntu-24.04` runners** for PR, nightly and release. They provide Docker (needed by Testcontainers and the Compose e2e stack) and 4 vCPU / 16 GB on public-repo runners. The 15-minute PR budget is met by running jobs in parallel.
- **Portability:** every job calls scripts in `ci/` or Gradle/pnpm tasks. The YAML only orchestrates, so a self-hosted runner, or GitLab for the bank later, reuses the same commands.
- **macOS runners** are not needed until Phase 6a (Q4).
- **Self-hosted runner option for the pilot:** the same workflows run with `runs-on: [self-hosted, linux]`. Documented in `ci/README.md`.

## R14 — Container base images and signing

| Image | Options | Choice |
|---|---|---|
| Backend JRE 25 | **`gcr.io/distroless/java25-debian13:nonroot`** (Temurin 25.0.4.1, uid 65532, no shell, Debian 13 security updates, Apache-2.0); Eclipse Temurin `25-jre-noble` (full Ubuntu userland, larger attack surface); Chainguard JRE (versioned tags need a paid plan; only `latest` is free); UBI9-minimal + Temurin (heavier) | **distroless**, **pinned by digest** (`sha256:28a3…f90e`). The update bot refreshes the digest. |
| Web static server | **`nginxinc/nginx-unprivileged:1.31.6-alpine3.24`** (uid 101, listens on 8080); Caddy (Apache-2.0, single binary, but smaller ops familiarity); busybox httpd (no proxying) | **nginx-unprivileged**, pinned by tag and digest. Config is generated from `web/security-headers.json`. |

**Signing:**

| Option | Air-gapped verification | Infrastructure |
|---|---|---|
| **cosign key-pair (`cosign sign --key`)** | yes: `cosign verify --key cosign.pub --insecure-ignore-tlog` works offline | private key in a CI secret (HSM or KMS later); public key published with each release |
| cosign keyless (Fulcio + Rekor) | needs the public Sigstore infrastructure | none to run |
| Notation (Notary v2) | yes, with an X.509 CA | needs a PKI |

**Recommendation: cosign key-pair** (cosign 3.1.3, Apache-2.0). We sign the image digest and attach the CycloneDX SBOM as a signed attestation (`cosign attest --type cyclonedx`). On-prem customers verify with the published `cosign.pub` and no internet access, which fits AGENTS §3.1 (no runtime dependency on public infrastructure). The signing key is **created by the owner** and stored as GitHub secrets; CI signs only when the secrets exist.

## R15 — Vulnerability, licence and SBOM tooling

| Need | Options | Choice |
|---|---|---|
| SBOM | **CycloneDX Gradle plugin 3.5.1** + **@cyclonedx/cyclonedx-npm 6.0.1** (native, exact resolved graphs) vs Syft 1.54 (scans artefacts, less precise for Gradle) | CycloneDX native plugins; Syft is not needed |
| Vulnerabilities (dependencies) | **Trivy 0.75** (scans SBOMs, images, filesystems and IaC; offline DB mirror possible; Apache-2.0) vs OWASP Dependency-Check (NVD API key, slow, many false positives) | **Trivy** on the SBOMs and on the images |
| IaC / Dockerfile scan | Trivy `config` (misconfiguration) vs Checkov | Trivy (one tool) |
| Licences | Trivy licence scanner (classification only) vs jk1 license-report (Gradle only) vs **our own check over both SBOMs** (`ci/license-check.mjs`: allow-list from AGENTS §3.1, explicit exceptions with reasons, Markdown report) | Own SBOM-based check: one allow-list and one report for both ecosystems; no extra plugin |
| Secrets | **gitleaks 8.30** (pre-commit + CI, MIT) | gitleaks |
| SAST | **Semgrep CE 1.180** (LGPL-2.1 engine, run as a separate CLI) with our custom rules; **SpotBugs 4.10 + FindSecBugs 1.14** (JVM bytecode, LGPL-2.1 / LGPL-3.0, dev-only); `eslint-plugin-security` (E03) | all three, as required by TESTING_STRATEGY §6 |
| Dependency pinning (transitive) | **Gradle dependency locking** (`gradle.lockfile` per project) + pnpm lockfile | Gradle locking (AGENTS §4: "all versions pinned") |

## R16 — Update bot

| | **Renovate** | Dependabot |
|---|---|---|
| Gradle version catalog + lockfiles | yes (catalog, `gradle.lockfile` maintenance) | catalog yes; lockfile maintenance limited |
| pnpm, Docker digests, GitHub Actions SHA pinning | yes (digest pinning with the tag kept as a comment) | yes; digest pinning for Actions limited |
| Grouping, schedules, automerge rules | rich | basic groups |
| Self-hostable (bank GitLab later) | yes (open-source CLI) | GitHub only |
| Setup | GitHub App install (owner) **or** self-hosted run in Actions with a token | built in |

**Recommendation: Renovate** (ADR-0014): portable to an on-prem Git server and better at version catalogs and digests. The owner installs the GitHub App, a one-time action. Until then nothing breaks.

## R19 — Supply-chain benchmark (SLSA, OpenSSF Scorecard)
- **SLSA v1.1 Build track.** L1 means provenance exists. L2 means hosted build plus signed provenance. L3 means a hardened, isolated builder (for example the `slsa-github-generator` reusable workflow).
- **Phase 0 target: Build L2.** That means GitHub-hosted runners plus an in-toto provenance attestation signed with our cosign key (`cosign attest --type slsaprovenance`). L3 is considered in Phase 7.
- **OpenSSF Scorecard** (`ossf/scorecard-action` 2.5.0) runs weekly once the repository is public or has the right token. Practices we already meet:
  - pinned dependencies (actions pinned by SHA, images by digest);
  - token permissions read-only by default;
  - SAST;
  - dependency update tool;
  - signed releases.

## Industry practice
- **Kubernetes and Sigstore projects:** they sign images by digest and attach SBOM and provenance as OCI attestations. Adopted.
- **GitLab and Grafana:** they publish a human-readable licence report with each release. Adopted (`licenses.md` artefact).
