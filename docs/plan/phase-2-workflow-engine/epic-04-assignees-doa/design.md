# E04 Assignees, routing rules and DoA — design

Decisions: ADR-0040 (resolution, queues, DoA, separation of duties, acting for). Builds on ADR-0034 (engine), ADR-0035 (data), ADR-0038 (definitions), ADR-0039 (conditions).

## 1. Module boundaries
- **`org`** gains a public read facade, `OrgDirectory` (no writes): holders of positions on a date, the position a user holds, a position's unit, a unit's head position and ancestors, and the reporting line N levels up. It already publishes `OrgChanged`.
- **`common.authz`** gains `RoleHolders`: the users holding a role in a unit (tenant-wide grants and grants scoped to the unit or an ancestor), implemented by `authz`.
- **`workflow`** owns resolution (behind a port the engine calls), queues, substitutions, DoA versions and rules, and the separation-of-duties checks. It reads `org` and `authz` only through those facades.
- **`memo`** offers the extended route (positions, groups), memo-type policies (allowed approvers, ad-hoc limits), and declared interests; it feeds DoA scope through its attributes (`memoTypeId`, `classificationRank`, `unitId` of the initiator's position).

## 2. Definition additions (schema 2, additive)
| Assignee kind | Fields | Resolves to |
|---|---|---|
| `position` | `positionId` | the position's holder today (the tenant's date) |
| `supervisor` | `levels` 1–5 | the holder of the position the initiator's position reports to, `levels` up |
| `unitHead` | `unit` (`"initiator"` or an id), `up` 0–5 | the holder of the head position of that unit, or of its ancestor `up` levels above |
| `role` | `roleId`, `unit` (`"initiator"` or an id) | users holding the role in that unit or an ancestor, or tenant-wide |
| `queue` | `queue` (key) | the queue's members (people, and holders of member positions) |

Step type **`doa`**: `{ "type": "doa", "name", "next" }`. Steps of the person types may add `fallback` (an assignee of kind `person` or `queue`), and `forward` limits for ad-hoc forwarding: `{ "within": "unit" | "legalEntity" | "anyone" }` (default `anyone`, E03's behaviour). Validator: referenced positions, roles and queues must exist in the tenant (problem `UNKNOWN_POSITION`, `UNKNOWN_ROLE`, `UNKNOWN_QUEUE`); a `doa` step needs a published DoA at activation, not at publish.

## 3. Data (migrations `workflow/V5`, `org` none, `memo/V4`)
- `workflow_step`: `resolution jsonb` (kind, inputs such as the position id and its holder, the rule and version for DoA, the people found and the people excluded with the SoD rule), `claimed_by uuid`, `needed int` (approvals a DoA group needs).
- `workflow_task.assignee_id` becomes nullable; `CHECK (assignee_id IS NOT NULL OR blocked_reason = 'UNRESOLVED')`; `blocked_reason` adds `UNRESOLVED`, `CONFLICT`.
- `workflow_queue (id, tenant_id, key, name, version…)`, `workflow_queue_member (queue_id, user_id | position_id)`.
- `workflow_substitution (id, tenant_id, principal_id, substitute_id, valid_from, valid_to, created_by, reason)`, no overlap per principal (exclusion constraint).
- `workflow_doa_version (id, tenant_id, number, state, effective_from, submitted_by/at, reviewed_by/at, review_remark, revision)` with `CHECK (reviewed_by <> submitted_by)` and one published version per effective date; `workflow_doa_rule (id, version_id, ordinal, name, subject_type, memo_type_id, unit_id, include_subtree, condition text, groups jsonb)`; `groups` = `[{ "needed": 2, "of": [{ "kind": "position", "positionId": … } | { "kind": "role", "roleId": … }] }]`.
- `workflow_instance.doa_version_id uuid` (pinned at start when the definition has a `doa` step).
- `memo_interest (memo_id, user_id, declared_by, reason, created_at)`; `memo_type`: `allowed_approvers jsonb` (positions, roles, people; empty = anyone), `doa_check boolean`.
- Every new table: `tenant_id`, forced RLS, composite foreign keys, `RlsMetaTest` coverage.

## 4. Runtime
### 4.1 Resolution
The runner gives the engine a `Resolver` (pure interface in `engine`, implemented in `internal`): `resolve(assignee, ResolveInput(initiator, initiatorPosition, subjectAttributes)) → Resolution(people, inputs)`. The engine, when it activates a person step, asks the resolver, removes people excluded by separation of duties (§4.4) and substitutes (§4.5), and then:
- **one person:** one task, as E03;
- **several people:** one task each, decided by the first action (mode `any`); the step may be **claimed** by one of them;
- **nobody:** the `fallback` if the step has one, else one blocked task without assignee (`UNRESOLVED`); `WorkflowTaskCreated` with `blocked` tells the reassigners (E01's notification).
The step's `resolution` records the kind, the inputs, the people and the exclusions. The history shows "Resolved to Rahim (holder of GM Credit)".

### 4.2 Claim and release
`POST /api/v1/tasks/{id}/claim` (a candidate of an unclaimed multi-person step): the step's `claimed_by` is set; the other candidates' tasks stay open but cannot be acted on (`CLAIMED_BY_OTHER`) and leave their inboxes' actionable list (shown as "claimed by …"). `POST …/release` (the claimer, or an administrator with `workflow.tasks.reassign`) clears it. Both are actions (`CLAIM`, `RELEASE`) in the history and audited. `current_assignees` = the claimer, or every candidate while unclaimed.

### 4.3 DoA steps
At activation the engine asks the DoA port for the instance's pinned version (pinned at start: the published version whose `effective_from` ≤ today, newest first). The first rule whose scope matches (subject and memo type, the initiator's unit within the rule's unit or subtree, the condition true) gives its groups. Group 1 activates as a step with `needed` = its count over the people its members resolve to (positions, roles), minus exclusions; when it has its approvals the next group activates; after the last, the walk continues at `next`. No rule matches, or a group cannot be filled: the step is held `UNRESOLVED` as in §4.1. The simulator (`POST /api/v1/admin/workflow/doa/simulate`) runs the same matching and resolution for given attributes and a unit, without an instance.

### 4.4 Separation of duties
Checked when a route is validated (submit) and whenever people are resolved:
| Rule | Check | On failure |
|---|---|---|
| SOD-1 | the initiator is never a candidate | excluded (E01 refuses them in a route) |
| SOD-2 | a person who already approved or recommended in this round is not a candidate again (tenant setting `workflow.sod.once.per.round`, default on) | excluded; a route listing them twice is refused (E01) |
| SOD-3 | a substitute never receives a task on an item they initiated, or one their principal is excluded from | the principal's substitution is not applied for this task |
| SOD-4 | people with a declared interest on the item (`memo_interest`) are not candidates; a holder can declare an interest on their open task (`POST /api/v1/tasks/{id}/recuse`, reason required), which closes it and re-resolves the step without them | excluded; recusal recorded (`RECUSE`) |
Exclusions are recorded in the step's `resolution`; when they leave nobody, the step is `UNRESOLVED` (never skipped). Rule decisions are in the history; `workflow.task.recused` and `memo.interest.declared` are audited.

### 4.5 Substitution and acting for
`workflow_substitution` rows are created and ended through `POST/DELETE /api/v1/admin/workflow/substitutions` (`workflow.tasks.reassign`; Phase 1b adds self-service). When a resolved person has a substitution in force today, their task goes to the substitute with `on_behalf_of` = the principal (E03's delegation model: actions record `acting_for_id`). Existing open tasks stay unless an administrator moves them (`POST /api/v1/admin/workflow/substitutions/{id}/apply`, which reassigns the principal's open tasks to the substitute, each a `REASSIGN` with reason). The inbox shows "for Rahim".

### 4.6 Staff changes
`OrgChanged(assignment)`: open tasks whose step resolved from a position re-resolve: the old holder's task is closed (`REASSIGNED`) and the new holder gets one (`REASSIGN` action, reason "holder of GM Credit changed"). `UserDeactivated`: the person's open tasks become blocked `INACTIVE` (E01's reassignment flow). Both in the changing transaction, lock order subject → instance (E01).

### 4.7 Extended initiator routes
Route entries: `person`, `position` (resolved at the entry's activation, as §4.1), or a `group` of people or positions with `mode` `all` or `any` (a parallel step). Memo types may restrict entries to `allowed_approvers` and may require the route to satisfy the DoA (`doa_check`): at submit, the DoA rule for the memo is matched and each group must be covered by route entries (problem `route:doa-missing` naming the group). Ad-hoc forwarding honours the step's `forward.within` (refused `FORWARD_OUTSIDE_LIMIT`).

### Invariants (E01, E03, plus)
19. A task without an assignee is blocked `UNRESOLVED` and cannot be decided.
20. A step with `claimed_by` set accepts decisions only from the claimer's task.
21. No person holds two decision tasks in one round unless SOD-2 is off.
22. Every activated person step has a `resolution`.

## 5. API sketch
| Endpoint | Purpose | Access |
|---|---|---|
| `POST /api/v1/tasks/{id}/claim` · `…/release` · `…/recuse` | claim, release, declare an interest | the candidate / claimer (engine), `Idempotency-Key` |
| `GET/POST/PUT /api/v1/admin/workflow/queues…` | queues and members | `workflow.queues.manage` |
| `GET/POST/DELETE /api/v1/admin/workflow/substitutions…`, `…/{id}/apply` | substitutions | `workflow.tasks.reassign`, step-up to apply |
| `GET /api/v1/admin/workflow/doa`, `…/versions/{n}`, `POST …/versions`, `PUT …/versions/{n}`, `POST …/submit`, `…/approve`, `…/send-back` | DoA lifecycle (as definitions) | `workflow.doa.manage` / `.approve` (step-up) |
| `POST /api/v1/admin/workflow/doa/simulate` | who approves this? | `workflow.doa.manage` or `.approve` |
| `POST /api/v1/memos/{id}/doa-preview` | the initiator's read-only preview | the memo's initiator |
| `POST/DELETE /api/v1/memos/{id}/interests` | declared interests | the initiator (draft) |
| `PUT /api/v1/admin/memo-types/{id}` | gains `allowedApprovers`, `doaCheck` | `memo.types.manage` |

## 6. Authorisation
New permissions: `workflow.queues.manage`, `workflow.doa.manage`, `workflow.doa.approve` (privileged, tenant-wide, granted to tenant administrators). DoA approve needs maker ≠ checker and step-up. Matrix file `p2-e04-assignees.yaml`.

## 7. Audit
`workflow.queue.created|updated`, `workflow.substitution.created|ended|applied`, `workflow.doa.created|draft_updated|submitted|sent_back|published`, `workflow.task.claimed|released|recused|reresolved`, `memo.interest.declared|removed`. Ids only.

## 8. Performance
Resolution is a handful of indexed lookups per activation (org tables are small and the tree is cached); the DoA version and its rules are cached per version id (immutable once published). Inbox unchanged: candidates hold ordinary task rows.

## 9. Web
- **Memo view:** "Resolved to … because …" under the step; Claim / Release on multi-person steps; Declare an interest (recuse) on a task; the initiator's DoA preview before submit; interests on a draft.
- **Route editor:** add a person, a position, or a group (all / any), limited to the allowed approvers; DoA problems listed per group.
- **Admin:** DoA (versions, rules table, review, simulator), queues and members, memo type allowed approvers and DoA check. Substitutions get a minimal admin list (1b does self-service).
- All under the `admin` catalogue part (T10 of E03), en/ar.
