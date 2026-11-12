# E04 Assignees, routing rules and DoA — log

## 2026-11-12
- Plan written under the delegated gate (Phase 2 Q1): research (R1, R5 in part, R6, R12), ADR-0040, design, threat model, tasks T1–T10, test plan, UX review notes. Implementation starts with T1.
- Choices worth knowing: resolution happens when a step activates and is recorded on the step (who and why); several candidates share a step through claim and release; nobody found means a blocked task for the administrators, never a skipped step; the DoA is tenant data with its own maker-checker and effective dates, pinned per instance; substitutions live in `workflow` until Phase 1b's delegation model, which may take them over.
- Out of E04 scope, recorded for later: the SoD exceptions report and overrides with justification and a second administrator; a related-party register; automatic release of claimed tasks after a time (E05); "offered to both" substitution mode (only "goes to the substitute" now); executive-committee ratification flags (research R6).
- Org has no acting assignments yet (`org_assignment.kind` is `PRIMARY` only, 1b), so acting-for comes from substitutions in E04.
