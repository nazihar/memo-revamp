# E02 Submissions and the escalation link — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows; ASVS 5.0 IDs as in `docs/security/asvs-checklist.md` (chapter-level).

## Data flows
1. An approver's action walks a memo into a waiting forum step. The engine stops, the submission is created in the same transaction, and the forum's officers become memo readers.
2. A secretary triages: accept, defer, return, decline or refer. Return and decline resume the memo through engine commands.
3. A decision (tests and the demo in E02, E06 later) resumes the memo with the outcome.
4. The initiator withdraws, or an administrator cancels: the submission closes as WITHDRAWN and the readers are retired.
5. Submitters and readers read the memo's forum panel. Officers read the queue.
6. The hourly sweep flags and reminds about waiting submissions.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Someone other than the forum's secretary triages, or decides on the committee's behalf | 2, 3 | Triage only by secretaries in office and holders of `forums.manage`; `ForumDecided` issued only by the forum module's decision service (no public endpoint in E02); matrix tests | V8 |
| T1 | A memo's route completes while the committee has not decided | 1, 3 | The waiting step has no task, so no actor can complete it except through `ForumDecided` or `ForumDeclined`; engine property test | V2 business logic |
| T2 | A submission is decided twice, or after the memo was withdrawn | 2, 3, 4 | Guarded status moves with `version`; the engine command checks that the step is still ACTIVE and FORUM; idempotency on the triage POSTs | V2 |
| T3 | Old pinned definitions change behaviour | 1 | `wait` absent means `false`; migration fits only same-id waiting steps | V2, V15 |
| R1 | A secretary denies returning or declining a paper | 2 | Audit with reason, the actor and acting-for; system note on the memo's note sheet; notification to the submitter | V16 |
| I1 | Forum officers read memos they should not | 1 | The `FORUM` reader kind exists only while a submission is open, under clearance (ADR-0050); retired on close | V8 |
| I2 | The queue shows memo subjects to someone who cannot read the memo | 5 | The queue shows the subject only where `MemoReadable` holds for the actor, otherwise the number and "restricted" | V8 |
| I3 | A reminder or triage email reveals a confidential subject | 2, 6 | Subjects take no parameters; bodies carry the memo number and the forum's name only | V14 |
| D1 | An escalation storm floods a queue | 1 | One submission per step entry (unique `(tenant_id, step_id)`); list pagination by cursor | V2 |
| E1 | An escalation to an inactive forum leaves a memo stuck | 1 | The listener refuses with `FORUM_INACTIVE` and rolls the action back; definitions are checked at publish (E01) | V2 |

## Residual risks
- Officers read the memo as soon as it is escalated, before triage. This matches practice (the secretariat prepares the papers) and is visible on the memo's access list.
