# E03 Definition model and runtime — research

All sources were accessed on **2026-11-11**. Research items come from the phase overview §6 (R1, R4, R5, R10, R11).

## R1 — Libraries this epic adds

| Library | Version | Licence | Status | Decision |
|---|---|---|---|---|
| CEL for Java, `dev.cel:cel` | 0.15.0 (Maven Central, 2026-10-27); 0.13.1 2026-06-12 | Apache-2.0 | Google project, moved to `github.com/cel-expr` in June 2026; regular releases | **Adopt** for conditions (R4). Brings `com.google.re2j:re2j` (linear-time regular expressions), `antlr4-runtime` 4.13.2 (BSD-3), `protobuf-java` 4.36.x and Guava 33.x, which we already ship (align the versions in the platform BOM). |
| JSON Schema validator, `com.networknt:json-schema-validator` | 3.0.8 (2026-09-30); 3.0.9 is 8 days old, skipped for the bake period | Apache-2.0 | Active; the 3.x line is built on Jackson 3 (`tools.jackson`), which matches the backend | **Adopt** to validate definition JSON against our schema (draft 2020-12). |

Sources: https://repo1.maven.org/maven2/dev/cel/cel/maven-metadata.xml, https://repo1.maven.org/maven2/dev/cel/cel/0.15.0/cel-0.15.0.pom, https://opensource.googleblog.com/2026/06/cel-finds-a-new-home-at-githubcomcel-expr.html, https://repo1.maven.org/maven2/com/networknt/json-schema-validator/maven-metadata.xml, https://repo1.maven.org/maven2/com/networknt/json-schema-validator/3.0.9/json-schema-validator-3.0.9.pom

## R4 — Condition language

A condition decides which branch an item takes (for example `item.amount > 100000 && item.category == "capex"`). It is written by tenant administrators, so it is untrusted input to the server: no code execution, bounded cost, clear errors.

| Option | Sandboxing | Cost limits | Admin-readable | Type checking before publish | Licence / maturity |
|---|---|---|---|---|---|
| **CEL** (`dev.cel:cel`) | Designed for untrusted policies (Kubernetes admission, IAM conditions, Envoy). Not Turing-complete; no I/O; only the variables and functions we declare | Without macros there are no loops: cost is linear in the expression's size. `CelOptions.comprehensionMaxIterations` limits macros if ever enabled. Regular expressions run on RE2 (linear time) | C-like syntax, familiar | Yes: compile against declared variable types and get an error with position before publishing | Apache-2.0, Google-maintained |
| SpEL with `SimpleEvaluationContext.forReadOnlyDataBinding()` | Spring documents the restriction as "best effort": anything reachable from the root object, accessors or resolvers can still be invoked; a mistake in the root object exposes methods | No built-in cost limit | Java-like | No static types; errors appear at evaluation | Apache-2.0, part of Spring |
| JsonLogic (`json-logic-java` forks) | Data-only rules, no code | Bounded by rule size | JSON trees are hard to read and write by hand; fine for a builder, poor for review | No | Several forks; the most active (`io.github.gzsombor`) is a single maintainer |
| Our own small DSL | Total control | Total control | As we design it | As we build it | We maintain a parser, a type checker and their fuzzing forever |

**Recommendation: CEL**, with standard macros off, a 1,000-character limit per expression, at most 20 conditions per definition, declared variables only (`item.*` from the subject's attribute catalogue), boolean result type enforced at compile time. Every evaluation that routes an item is stored with its inputs (the attribute values read) and result. JsonLogic stays a candidate representation for E06's builder only if CEL text proves hard to edit; the builder can generate CEL.

Sources: https://github.com/google/cel-java, https://javadoc.io/static/dev.cel/cel/0.9.0/dev/cel/common/CelOptions.html, https://v1-33.docs.kubernetes.io/docs/reference/using-api/cel/, https://docs.spring.io/spring/reference/7.1/core/expressions/evaluation.html, https://github.com/jamsesso/json-logic-java, https://mvnrepository.com/artifact/io.github.gzsombor/json-logic-java/versions

## R5 — Step actions in e-office and memo products

- **NIC eOffice (eFile)**: the file moves by **Send** to a chosen recipient with a green note (noting); yellow notes are drafts approved before they become green; forwarded items stay in the sender's sent items with a "With user" column (who holds it now). There is no fixed process graph: routing is the officers' choice. Sources: https://www.scribd.com/document/729189122/EOffice-User-Manual, https://www.deendayalport.gov.in/wp-content/uploads/2022/11/19Feb2021051839PM.pdf, https://apsrtc.ap.gov.in/Eoffice%20Manual%207X%20V1.pdf
- **Newgen eGov office**: office notes routed for approval with track sheets; green-note noting per the Manual of Office Procedures; decisions captured in the file. Sources: https://newgensoft.com/solutions/industries/government/e-gov-office, https://landing.newgensoft.com/hubfs/_2020%20Website%20files/Brochures/Brochure_%20Newgen%20solutions%20for%20eGov%20Office.pdf
- **What we adopt:** "with whom now" on every list (E01 has it); notes (our remarks and opinions) kept in order on the item; the holder can forward with a remark. **What we do better:** the path is a published definition, so auditors can see which steps were required, and every deviation (send back, forward, delegate) is a recorded action with its reason.

## R10 — Definition versioning and in-flight migration

- **Camunda 7** pins every process instance to the definition version it started on. Moving instances takes an explicit **migration plan**: instructions that map source activities to target activities, validated twice (when the plan is built, and per instance before execution); an instance whose active activity has no instruction fails validation and stays where it is. The Cockpit migration screen is enterprise-only. Sources: https://docs.camunda.org/manual/7.8/user-guide/process-engine/process-instance-migration/, https://docs.camunda.org/manual/7.17/reference/rest/migration/validate-migration-plan, https://docs.camunda.org/manual/webapps/cockpit/bpmn/process-instance-migration/
- **SAP Flexible Workflow** activates a new workflow version for new documents; documents already in approval continue on the version they started with. Source: https://learning.sap.com/courses/sap-workflow-overview-basics-strategy-and-extensibility/explaining-flexible-workflow
- **Adopt:** pin to the version; migration only by an administrator, with a **preview per instance** and a refusal for instances whose current step has no counterpart. **Simplify:** map steps by their stable `id` in the definition (an author who keeps ids keeps instances migratable); no hand-written mapping in E03. **Schema evolution of the JSON itself:** a `schemaVersion` field; the server upgrades older bodies on read (E01's built-in body is schema 1 and becomes a schema-2 body with one `route` step).

## R11 — Industry benchmark (step actions, routing, consultation, history)

| Capability | SAP Flexible Workflow / My Inbox | ServiceNow (Flow Designer "Ask for Approval") | NIC eOffice / Newgen eGov | Kissflow (process) | memo (E01 + E03 plan) |
|---|---|---|---|---|---|
| Fixed steps with conditions | Start conditions and step preconditions; automatic-release steps | Approval rules over users and groups; script for complex rules | None (free routing) | Branches with conditions; "goto" between steps | Published definition: steps, branches with CEL conditions |
| Parallel approval | Several recipients per step | Anyone / all / all responded and anyone / percentage / number of users; separate rejection rules | Not modelled | Parallel branches with conditions | Parallel step: all, any, n of m; reject and return rules stated per mode |
| Send back / return | Rework to the requester | Rejection ends the approval | Return to sender | Send back to a chosen earlier step; to the last approver of that step | Return to initiator (E01), **send back to an earlier step** (E03) |
| Forward / reassign by the holder | Forward in My Inbox | Delegation records | **Send** to anyone (core action) | Reassign | **Forward** (responsibility moves) and **delegate** (acts for me) |
| Consultation | — | — | Noting by others on the file | — | **Refer for opinion** (task stays with the referrer); **seek clarification** (and answer) |
| Excluding the requester as approver | "Exclude restricted agents" step property | — | — | — | Initiator never approves (E01); SoD rules in E04 |
| Versioning | New version for new documents | Flow versions | — | Versions | Pinning + admin migration with preview (E03) |
| History | Workflow log | Approval history | Track sheet / "with user" | Item timeline | Append-only action history per round with document hash; condition evaluations with inputs |

Sources: https://learning.sap.com/courses/implementing-sap-s-4hana-cloud-public-edition-sourcing-and-procurement/defining-workflows-in-sourcing-and-procurement_a17030df-33b7-4c0e-a8c6-31ec490618e9, https://community.sap.com/t5/technology-blog-posts-by-sap/sap-s-4hana-flexible-workflow-overview-and-comparison-with-sap-business/ba-p/13390529, https://docs.servicenow.com/bundle/utah-build-workflows/page/administer/flow-designer/reference/ask-approval-flow-designer.html, https://www.servicenow.com/docs/r/zurich/build-workflows/workflow-studio/ask-approval-action-designer.html, https://community.kissflow.com/t/m1h9qmv/making-a-process-workflow, https://docs.kissflow.com/article/ahlquze7te-process-workflow, plus the eOffice and Newgen sources under R5.

**Gaps recorded (adopt / later / deliberately not):**
- *Adopt in E03:* ServiceNow's explicit rule for what a rejection does in a parallel group (we state it per mode); Kissflow's send-back to a chosen earlier step; eOffice's "with whom now".
- *Later:* percentage-based parallel approval (n of m covers the pilot); automatic-release steps (an E04 resolver or condition can skip a step); deadline e-mails (E05).
- *Deliberately not:* free routing without a definition (E04's ad-hoc routing is bounded by allowed units and levels); script-based approval rules (conditions stay in CEL).
