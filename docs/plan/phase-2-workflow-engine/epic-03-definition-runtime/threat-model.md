# E03 Definition model and runtime — threat model

STRIDE over the new flows: definition authoring and publication, condition evaluation, the new task actions, recall, cancellation and migration. ASVS 5.0 references in brackets.

| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | A user acts on a task they do not hold, or as someone else | task actions | The engine checks the holder per task (E01); a delegate's action records `acting_for_id`; the actor comes from the session, never from the body | V8.2.1, V8.2.2 |
| S2 | A definition author publishes their own change | lifecycle | Maker-checker in code and a `CHECK (reviewed_by <> submitted_by)`; approve and retire need step-up | V8.2.3, V6.4 |
| T1 | A definition is altered after publication, silently changing running items | definitions | Published versions are immutable (updates refused on non-draft rows by the store and a trigger); instances pin the version; sub-workflows are inlined at publish | V2.1, V15.3 |
| T2 | History rewritten to hide a send-back, forward or delegation | history | `workflow_action` append-only (E01: trigger and revoked privileges); every new action is a row with its remark and details | V16.3.3 |
| R1 | Nobody can show why an item went one way | branches | Every branch evaluation stored with the attribute values read and the result (`ROUTE`, `details`) | V16.2.1 |
| R2 | A migration cannot be traced | migration | `MIGRATE` action per instance (from, to) and an audit entry per instance; the batch needs step-up | V16.2.1 |
| I1 | A condition or definition leaks data across tenants | definitions | Definitions are tenant rows under forced RLS; fragment references resolve inside the tenant; conditions see only `item` with declared attributes of the current subject | V8.4.1 |
| I2 | Opinion or clarification holders read more than their part | consultation | They may read the memo while their task is open (participation, plus clearance as in E01); after it closes they keep read access as participants (as E01 approvers do) | V8.2.2 |
| I3 | Remarks (business content) end up in the audit store or e-mails | audit, notify | Audit payloads carry ids only; `notify` messages carry the memo reference and a link, never the subject or remarks (AGENTS.md §3.4) | V14.2.1 |
| D1 | An expensive condition stalls the server | conditions | CEL without macros (no loops), RE2 regular expressions, 1,000 characters per expression, 20 conditions per definition; compiled programs cached; Jazzer fuzz target nightly | V2.4, V15.2 |
| D2 | A huge or deep definition exhausts memory | import | Body ≤ 1 MiB (E01 CHECK), ≤ 100 steps, ≤ 20 parallel assignees, ≤ 10 branches per step; JSON Schema first, then semantic checks | V2.2.1 |
| D3 | Referral floods (refer to many people, repeatedly) | refer | ≤ 10 opinion tasks per referral and ≤ 30 open opinion tasks per step; the general rate limits apply | V2.4 |
| E1 | Code execution through conditions | conditions | CEL only, no SpEL, no scripting, no reflection; declared variables only (ADR-0039) | V1.3, V15.2 |
| E2 | Cancel or migrate by a non-administrator | admin | Privileged permissions, step-up, matrix rows for every endpoint (deny by default) | V8.1.1 |
| E3 | Forward or delegate to someone who is not cleared, or to the initiator | forward / delegate | Eligibility (active, clearance) checked as for reassignment; the initiator and people already on the step refused | V8.2.2 |
