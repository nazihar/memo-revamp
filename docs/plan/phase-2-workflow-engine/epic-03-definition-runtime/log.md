# E03 Definition model and runtime — log

## 2026-11-11
- Plan written under the delegated gate (Phase 2 Q1): research (R1, R4, R5, R10, R11), ADR-0038 (definitions, lifecycle, migration), ADR-0039 (CEL conditions), design, threat model, tasks, test plan, UX review notes. Implementation starts with T1 and T2.
- Choices worth knowing: sub-workflows are inlined at publish (no child instances, so the one-instance-per-subject rule of E01 holds); loops are not edges (going back is the send-back action); review steps record **Recommend**, approve steps **Approve**, so the history says which one a person did.
