# E04 Assignees, routing rules and DoA — threat model

STRIDE over resolution, queues and claims, substitutions, DoA maintenance and the separation-of-duties checks. ASVS 5.0 references in brackets.

| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Someone acts on a queue task without being a candidate, or after another claimed it | claim | Candidates hold their own task rows; the engine refuses the non-claimer while `claimed_by` is set; the actor comes from the session | V8.2.1 |
| S2 | A substitute acts as the principal on the principal's own interests | substitution | SOD-3: no substitution for items the substitute initiated or the principal is excluded from; actions record `acting_for_id` | V8.2.2 |
| T1 | DoA bands changed quietly to let a memo through | DoA | Versions with maker ≠ checker (CHECK), step-up, effective dates; instances pin the version in force at start; published versions immutable | V8.2.3, V15.3 |
| T2 | Resolution falsified after the fact | resolution | `resolution` written with the step in the action's transaction; steps and actions append-only as in E01 | V16.3.3 |
| R1 | Nobody can show why X was asked to approve | resolution | Kind, inputs (positions, rules, version), people found and excluded recorded per step and shown in the history | V16.2.1 |
| R2 | A recusal or exclusion cannot be traced | SoD | `RECUSE` action with the reason; exclusions with the rule id in `resolution`; audit `workflow.task.recused` | V16.2.1 |
| I1 | Resolution reveals org data of another tenant | resolvers | Facades run inside the tenant transaction under forced RLS; positions, roles and queues in definitions are checked to exist in the tenant | V8.4.1 |
| I2 | The DoA preview reveals people to an initiator who could not otherwise see them | preview | It shows names and position titles only (as the people picker does), for the initiator's own draft | V8.2.2 |
| D1 | A definition or DoA rule resolves to thousands of people | resolvers | At most 20 candidates per step (more → refused at activation as `TOO_MANY_CANDIDATES`, held unresolved); role and queue sizes checked in the simulator | V2.4 |
| E1 | A user grants themselves approval authority through the DoA | DoA | `workflow.doa.manage` and `.approve` are privileged; the checker cannot be the maker; every change audited | V8.1.1 |
| E2 | Skipping a step because nobody was found | runtime | Never skipped: unresolved steps hold a blocked task until an administrator assigns it (invariant 19, property-tested) | V2.1 |
