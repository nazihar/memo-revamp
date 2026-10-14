# Test plan — E01 Walking skeleton

## Scope and risks
This epic adds the skeleton plus one platform slice (health). The main risks:
- information disclosure from the public endpoint (see `threat-model.md` I1, I3);
- downstream timeouts;
- toolchain drift.

See [design.md](design.md) and [threat-model.md](threat-model.md).

## Tests per slice

| Slice | Layer | Test (behaviour) | Data / fixtures | CI stage |
|---|---|---|---|---|
| T1 | Unit (KMP commonTest on JVM) | shared `HealthStatus` worst-of combines statuses correctly | — | PR |
| T2 | Architecture | `ModularityTest`: expected modules exist, `verify()` passes | — | PR |
| T2 | API integration | `SecurityDefaultsTest`: unknown `/api/**` route → 401 problem; health permitted anonymously | MockMvc | PR |
| T2 | API integration | `ProblemDetailsTest`: an unhandled exception → 500 `application/problem+json`, no stack trace or exception message | test controller | PR |
| T4 | Unit | `HealthAggregationTest`: overall-status rules (DB down → DOWN; other component down → DEGRADED; DISABLED ignored) | — | PR |
| T4 | Unit | `HealthServiceTest`: result cached for 5 s (controlled `Clock`); probe timeout → DOWN | fake probes | PR |
| T4 | Unit | `ClamdProbeTest`, `SmtpProbeTest`: PONG/220 → UP; other reply, refused connection or silent socket → DOWN within the timeout | in-test `ServerSocket` | PR |
| T4 | Persistence | `SystemInfoRepositoryIT`: migration applies to an empty DB, the marker row is readable through jOOQ | Testcontainers PostgreSQL | PR |
| T4 | Integration | `ObjectStorageProbeIT` + `StorageBootstrapIT`: SeaweedFS + init script → `records` has object lock, the app identity cannot change lock config, the probe is UP | Testcontainers SeaweedFS + aws-cli | PR |
| T4 | API integration | `HealthEndpointIT`: real PostgreSQL; exact JSON field set; 200/503 rules; no host or version strings | Testcontainers | PR |
| T5 | Contract | `OpenApiContractTest`: generated spec equals `contracts/openapi.json`, `openapi` = 3.1.x | — | PR |
| T6 | Web component | `health.test.tsx`: loading → data → error states; status shown as text | MSW handler typed from generated types | PR |
| T6 | Contract | `pnpm typecheck` compiles the generated client | — | PR |
| T7 | Smoke (manual in E01, Playwright in E03) | `pnpm dev` → health page shows UP | Compose full | E03: PR |

## Authorization matrix tests
`design.md §5`: 2 allow cases (health anonymous, api-docs when enabled) and 2 deny cases (unknown API route, api-docs when disabled). All four are in `SecurityDefaultsTest`.

## State-machine tests
None (no state machines in this epic).

## Security tests
S1 → `SecurityDefaultsTest`; I1 → `HealthEndpointIT`; I3 → `ProblemDetailsTest`; D1/D2 → `HealthServiceTest`; E2 → `StorageBootstrapIT`; T1 → `OpenApiContractTest`.

## Performance
Cached health p95 < 20 ms. Checked by the Gatling skeleton in E05.

## Accessibility and i18n
The health page uses semantic HTML and does not rely on color alone. axe runs from E03. Strings sit in a message map that is replaced in E03.

## Exploratory charters
1. Try to get any version, host or stack-trace information out of the backend without credentials (30 min).
2. Stop each stack service in turn and watch health transitions and response times (30 min).

## Acceptance mapping
| Criterion | Test |
|---|---|
| T1 build | CI backend job |
| T2 modules / deny | `ModularityTest`, `SecurityDefaultsTest` |
| T3 bucket lock | `StorageBootstrapIT` |
| T4 status rules | `HealthAggregationTest`, `HealthEndpointIT` |
| T5 drift | `OpenApiContractTest` |
| T6 page | `health.test.tsx` |

## Exit checklist
- [ ] All listed tests implemented and green in their stage
- [ ] No new quarantined tests
- [ ] ASVS checklist skeleton created with the E01 rows
