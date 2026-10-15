# Test plan — E04 CI pipeline and supply chain

## Scope and risks
The gates themselves are the product of this epic. Each gate must be proven to **fail** on a bad input, not only to pass on the current repository.

## Tests per slice
| Slice | Test (behaviour) | How | Stage |
|---|---|---|---|
| T1 | A dependency changed without `--write-locks` fails resolution | local negative run | once (documented) |
| T1 | SBOMs contain the resolved runtime graph (e.g. `spring-core`, `react`) | `ci/license-check.mjs --self-test` | PR |
| T2 | A GPL/AGPL/unknown licence fails; an allow-listed one passes; exceptions need a reason | `node ci/license-check.mjs --self-test` (built-in fixtures) | PR |
| T3 | Every custom Semgrep rule fires on its bad fixture and stays silent on its good one | `semgrep --test .semgrep/` | PR |
| T3 | gitleaks catches a planted fake key; the repository is clean | local negative run + `security` job | PR |
| T3 | SpotBugs + FindSecBugs clean on backend main | `./gradlew :backend:spotbugsMain` | PR |
| T4 | Images build, run non-root on a read-only root FS; web serves CSP and proxies `/api` to a running backend | `ci/image-smoke.sh` | PR (`images` job) |
| T4 | No critical or high vulnerabilities in the images | Trivy image | PR |
| T5 | Workflows are valid | actionlint | PR |
| T5 | ADR index lists every ADR | `ci/check-adr-index.mjs` | PR |

## Exit checklist
- [x] All listed tests implemented and green locally (each gate proven to fail on bad input); CI green once pushed — **blocked on push permission**
- [x] No new quarantined tests
- [x] ASVS checklist updated (V15 rows)
