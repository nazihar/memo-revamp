# Phase 1b — log

History of the phase, newest last. `STATUS.md` keeps only the current position.

## 2026-11-17
- Phase 1b starts after Phase 3 E06 and before E07 (Phase 3 Q4). It must finish before Phase 3 exits.
- Overview written (`overview.md`): ten epics in three tracks. Identity: E01 federated sign-in → E02 passkeys and the sign-in policy → E03 mobile token issuer (the critical path, with E10). Organisation: E04 organisation in full → E05 HR synchronisation. Keys and tenant lifecycle: E06 key management → E08 export and offboarding, with E07 break-glass beside them. Then E09 admin console completion and E10 close-out with the ASVS L2 review of 1a + 1b. About 30–40 team-weeks; critical path about 12–16. Q1–Q5 sent to the owner (PR #141).
- Found while reading: ADR-0028 places crypto-shredding in Phase 7, but the master plan puts it in 1b's scope and exit criteria (and again in Phase 7); 1b follows the master plan and ADR-KMS amends ADR-0028. The master plan's ASVS list for 1b (V6–V9, V11, V14, V16) leaves out V10 (OAuth and OIDC), which SSO and the token issuer fall under; the overview adds it. Phase 2 Q3 expected acting-for to be tested with org acting assignments, but `org_assignment` allows only `PRIMARY`, so Phase 2 used workflow substitutions; E04 and E10 check the exit item again.
