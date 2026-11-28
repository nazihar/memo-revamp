# Decision register and committee analytics

**When:** an auditor or the company secretary asks for "every resolution of the Board Risk Committee last year", someone asks "why can't I find this resolution?", "why does this line say Restricted item?", "why is the register missing yesterday's decision?", "why can't I export?", "why is the attendance figure different for me?" or "is this export what memo held?"; or after a PostgreSQL major upgrade. Background: ADR-0069 (the decision register), ADR-0070 (meeting analytics), ADR-0054 (search behind definer functions), ADR-0062 (recusal), ADR-0065 (decisions and resolutions), Phase 4 E08 `design.md`. Decisions, numbers, minutes and extracts are in [minutes-and-resolutions.md](minutes-and-resolutions.md); the search function's privileges and upgrades in [search.md](search.md); actions in [action-items.md](action-items.md).

## Who does what

| Who | Register (`/decisions`) | Analytics (`/analytics/committees`) |
| --- | --- | --- |
| The committee's secretaries, the chair and the vice-chair in office today | Search their committees; every row in full (within clearance) except items they declared an interest in; export their **officer** committees; run the number check. A vice-chair is an officer here only when the committee has no chair in office; otherwise they read it as a member (and do not export it) | Their committees: every measure, every member's attendance, late papers; the attendance export |
| Holders of `forums.manage` | Every committee, as an officer | Every committee; the attendance export |
| Holders of `forum.read` (internal audit; granted on purpose through a tenant role, no built-in role has it) | Every committee: unrestricted items in full, restricted, title-hidden and members-only items as lines; export; number check | Every committee; the attendance export |
| Members in office (chair, vice-chair or member seat today) | Search their committees: unrestricted items and items restricted to them in full, the rest as lines. **No export** (403) | Their committees' totals, **their own** attendance row, the meeting-level share of papers on time (no late counts); no export |
| Former members, guests, item attendees, observers, alternates without a seat of their own, memo readers, action owners outside the committee, everyone else | 403 (the memo's readers see its resolution on the memo) | 403 |

A row or a held meeting above the viewer's clearance is not shown and not counted, for anyone. `forum.read` and `forums.manage` are privileged: their holders sign in with a second factor.

## The register's rule

- **One row per decision**, every committee of the tenant: a resolution is a decision with a number; "noted", "deferred" and procedural decisions are rows too. It holds the committee, meeting, date, item number and title, outcome, text, conditions, validity, numbers, state (Recorded, Numbered, Released, Final, Void), the memo and the exception flags. **Never** the secretary's notes, the minutes' discussion, papers, declarations' interests, voters' or dissenters' names (only "with dissent"), or override reasons. So no word search can find an item by what was said about it.
- **Three levels per row, computed per request** (`DecisionRegisterAccess`, then one SQL function, `search_find_decisions`):
  - **Full:** everything above.
  - **Restricted line:** committee, meeting and date, item number, the resolution number and whether it is in force or void, and "Restricted item" with the reason: "You declared an interest in this item" (the viewer's declaration, a pending ruling, or the declaration of the member they stand in for) or "Restricted to named members". No title, outcome, text, conditions, memo or flags.
  - **Nothing:** outside the viewer's committees, or above their clearance. Not returned, not counted.
- **Recusals are read in the request**, not copied onto rows: a declaration applies to the next search the moment it is committed; a withdrawn one gives the row back at once.
- **What matches a line:** committee, committee type, dates, state, "resolutions only" and a **resolution number** (it is on the line). Words, outcome, memo type, memo number and the five exception flags (with conditions, with dissent, to be ratified, corrected, validity passed) match **full rows only**, and outcome facets count full rows only. A line's content is unknown to the viewer, so matching on it would reveal it.
- **"Why can't I find it?"** In order: the person has no register (403: not a seat in office, no `forum.read`); the committee is not in their scope (a member of the Board does not see the Audit Committee); the item is above their clearance; it is a restricted line and they searched by words or outcome (search its number instead); the row is missing or behind (the unindexed check below); the words: the last word is matched as a prefix only from 3 letters, and a number-like text is looked up whole as a resolution or memo number.

## Indexing, the backfill and the rebuild

- **Indexing** happens in the transaction of each change (a decision recorded or corrected, a number given or voided, the release to the memo, the minutes approved, an agenda revision published, a meeting renamed, rescheduled, numbered or reclassified). A rolled-back change rolls its row back. Recusals are never indexed.
- **The check and the backfill** (db-scheduler, `MEMO_JOBS_ENABLED=true`):

  | Task | When | What |
  | --- | --- | --- |
  | `search-decisions-check` (platform) | Daily (fixed delay of a day from its first run) | Schedules `search-decisions-backfill` for every active tenant (`scheduleIfNotExists`, instance id = the tenant id) |
  | `search-decisions-backfill` (per tenant) | When scheduled | Writes the rows that are missing or behind (another current entry, or minutes approved since), in chunks of 500, each in its own transaction, up to 120 chunks (60,000 decisions) a run; resumable and idempotent. Audits `search.decisions.backfilled` (`rows`, `rebuild: false`) and sets the metric `memo.search.decisions.unindexed` |
  | the same task with `rebuild: true` | By hand (below) | Rewrites **every** row in id order, a chunk of 500 per execution, each chunk scheduling the next (`rebuild:<tenant>:<last id>`), so a stopped rebuild resumes; audits `search.decisions.backfilled` with `rebuild: true` and the total at the end |

  The check can be switched off with `memo.search.backfill-sweep=false` (tests only). A tenant with more than 60,000 missing rows needs two runs: schedule the backfill again (below) instead of waiting a day.
- **Measured durations** (E08 T5, `RegisterVolumeIT`, the dev machine with PostgreSQL in Docker; quiet / loaded runs):

  | Work | Decisions | Took | Budget |
  | --- | --- | --- | --- |
  | Backfill of an empty register | 55,152 | 475 s (8 min) / 687–1,154 s | 10 min (CI limit 20) |
  | Backfill of an empty register | 114,822 | 1,485 s (25 min) | scaled: 1,650 s (CI limit 3,300 s) |
  | Indexing one recorded decision | — | 16–21 ms / 54–66 ms (14 statements) | 20 ms |
  | An agenda revision's refresh | 47 decided items | 27–38 ms | 50 ms |

  The volume's ids are random, so its chunks span many meetings; production ids are time-ordered and a chunk holds a few meetings, so expect the same or better. A rebuild does the same work, plus up to one scheduler poll (10 s) between chunks: allow the backfill's time plus about 20 minutes per 55,000 decisions. It was not timed on its own.
- **Rows stay searchable during a rebuild** (each chunk upserts; nothing is deleted), and decisions recorded meanwhile are indexed by their own transaction.

### The unindexed check
`search_decision_unindexed(tenant, limit)` lists decisions without an up-to-date row (at most 1,000). Expected: empty after the backfill. As the schema owner (or the application role), inside a transaction:

```sql
begin;
select set_config('app.tenant_id', '<tenant id>', true);
select count(*) from search_decision_unindexed('<tenant id>', 1000);
commit;
```

- The metric `memo.search.decisions.unindexed` is the sum over tenants as of each tenant's last backfill run (up to 1,000 each). Above zero after a run means rows the backfill could not write: the log has `search: decision <id> has no meeting or item to index`. That decision's meeting or item cannot be read (data damage, not a register problem): investigate it with the minutes runbook; the backfill tries it again at every run.
- **Run the backfill now** (instead of waiting for the daily check), as the schema owner:

  ```sql
  insert into system_scheduled_task (task_name, task_instance, task_data, execution_time, picked, version)
  values ('search-decisions-backfill', '<tenant id>',
          convert_to('{"tenantId":"<tenant id>","rebuild":false,"after":null,"rows":0}', 'UTF8'), now(), false, 1)
  on conflict do nothing;
  ```

### The rebuild
Run it after a change to what a row holds (a release that changes the body's make-up says so) or when rows are suspected wrong. **After a PostgreSQL major upgrade** a rebuild is not needed: recompute the vectors in place ([search.md](search.md), "The decision register").

```sql
insert into system_scheduled_task (task_name, task_instance, task_data, execution_time, picked, version)
values ('search-decisions-backfill', 'rebuild:<tenant id>:start',
        convert_to('{"tenantId":"<tenant id>","rebuild":true,"after":null,"rows":0}', 'UTF8'), now(), false, 1);
```

Follow it in the scheduler table (`task_instance like 'rebuild:<tenant id>:%'`); it is done when the audit has `search.decisions.backfilled` with `rebuild: true`. Then run the unindexed check.

## Exports and what the audit holds

- **`POST /api/v1/decision-register/export`** (`{format: XLSX|PDF}` with the search's filters and order), from the export dialog on the register. Officers who are not oversight export their **officer** committees only (another committee is 422 `FORUM_OUT_OF_SCOPE`); members get 403.
- **Limits:**

  | Format | Rows | Measured (E08 T5) | Budget |
  | --- | --- | --- | --- |
  | XLSX | 20,000 | 19,992 rows in 1.5–1.6 s / 3.4 s | 10 s |
  | PDF | 2,000 | 1,989 rows in 7.8–10.1 s / 21.9 s | 15 s |

  More rows is 422 `format:TOO_MANY_ROWS` with `rows` (or `moreThan`) and `maximum`. With words an export takes at most 10,000 candidates, so a word search matching more is too many for either format: narrow it (a committee, a year). The dialog shows the count first and disables Download above a PDF's 2,000.
- **Rate limit:** `REGISTER_EXPORT_PER_USER`, 5 an hour per person and tenant, counted after the 403 and 422 checks (a refused export spends nothing); 429 with `Retry-After`.
- **The spreadsheet:** "Decisions" (18 columns; a line fills committee, meeting, date, item, "Restricted item: …", number, number state, state and link only); "About this export" (organisation, who, when in UTC and in the tenant's zone, the filters in words, the counts, "Restricted items show their number only."); "Number check" (per committee and period of the rows' numbers: first, last, in force, void, missing, duplicates, outside the range, all accounted for) and "Void numbers" (the reason only when that decision is a full row of the same export). Every cell is a string; a value starting with `=`, `+`, `-`, `@`, tab, CR or LF gets a leading apostrophe.
- **The PDF:** "Register of committee decisions", landscape, the about block, a table per committee in date order, the check and the voids, "Produced by {name} on {time} — {filters}" and "Page n of N" on every page. Not a record and never stored: the records are the approved minutes and certified extracts.
- **The audit**, written in the export's transaction **before** the file is written (`search.decisions.exported`, object `search`, the exporter): `format`, `filters` (ids, dates, kinds), `sort`, `textSha256` (SHA-256 of the trimmed, lower-case words with single spaces), `terms` and `numberLookup`, **never the words**; `rows`, `lines` (restricted lines) and `rowsSha256`. If the download then breaks, the audit still says it was exported.
- **Searches** are audited too (`search.decisions.performed`: the same word hash, filters, order, `nextPage`, total, capped and the page's decision ids). Application logs never carry the words. Analytics reads and the scope read are not audited.
- **Matching an export to the register** ("is this the file memo produced?"): find the entry (Administration → Audit, action `search.decisions.exported`, the person and the time) and compare `rows` and `lines` with the file. `rowsSha256` is SHA-256 over the sorted `decision_id:entry_seq` pairs joined by line feeds. Each row of the file is one decision (one per item: the link holds the meeting, the item column its number); the entry in force at the export's time is the highest `seq` of `minutes_decision_entry` recorded before it. As the schema owner, with the decision ids:

  ```sql
  select encode(sha256(convert_to(string_agg(p, E'\n' order by p collate "C"), 'UTF8')), 'hex')
  from (select e.decision_id::text || ':' || max(e.seq) as p
        from minutes_decision_entry e
        where e.tenant_id = '<tenant id>' and e.decision_id = any('{<ids>}') and e.recorded_at <= '<export time>'
        group by e.decision_id) s;
  ```

  Run it inside a transaction with `app.tenant_id` set, as the check above (the minutes tables' row security is forced). Equal: the file held exactly those decisions as they stood. To check a stated search, hash the words the same way and compare with `textSha256`.

## Serving an auditor's request

1. **Give the auditor a register of their own:** a tenant role with `forum.read`, granted on purpose (Administration → Roles), with the clearance their engagement needs. They then search every committee within that clearance and export. Never export on their behalf from a secretary's account: the secretary's register includes items the auditor may not see in full, and the audit would name the wrong person.
2. **Restricted items stay lines** for `forum.read`: number, meeting and date only. If the engagement needs one in full, the committee decides; the secretary (who sees it) can issue a certified extract of the resolution ([minutes-and-resolutions.md](minutes-and-resolutions.md), "Certified extracts"), which is audited and checkable by its hash.
3. **"Every resolution of the BRC in 2025":** committee BRC, dates 1 Jan–31 Dec 2025 (dates are meeting dates in the tenant's zone), "Resolutions only", Number order, then export XLSX. The "Number check" sheet shows the series is complete: missing, duplicate or out-of-range numbers are an incident (minutes-and-resolutions.md, Monitoring).
4. **The minutes themselves:** the approved minutes record (PDF/A, object-locked) from each meeting's Minutes, with the Check that re-hashes it. A `forum.read` holder reads the records they may see.
5. **Attendance for the annual report:** Committee analytics → the committee → Attendance → "Export for the annual report" (XLSX, one row per seat holder, at most five years).
6. **Other registers** they may ask for: declarations of interest (`/declarations`, "Export to Excel", meetings-and-agendas.md) and the action tracker's export (action-items.md). Each is audited with its filters and count.
7. **Tell the auditor** that the files are confidential copies (they hold decisions' text), that each export is audited with a hash of its rows, and that the register is live: a later correction changes the row (its entry number rises and "corrected" is set), never the minutes record.

## Analytics: the definitions

All five come from one definition each (`analytics.measures`), used by the screens, the export and the tests. **Periods** are dates in the tenant's zone (`default.timezone`; default the last 12 months to today, at most 10 years; per-member rows at most 5 years, otherwise the detail omits them and the export answers 422). **Held meetings** are meetings in state Held, Minutes approved or Closed whose date, in the meeting's own zone, is in the period. Everything is computed live for the viewer from what they may see; nothing is stored or cached.

| Measure | Definition |
| --- | --- |
| **M1 Attendance** | For each person holding a seat (chair, vice-chair, member) on a held meeting's date: expected = held meetings during their term; attended = their own register line reached present (in person or online) at any time, after voids (joining late, leaving early and stepping out of an item still count); else apologies; else absent. "By alternate": apologies with their alternate present, shown but never counted as the member's. Rate = attended ÷ expected; the committee's = Σattended ÷ Σexpected. Whole percents, rounded half up |
| **M2 Actions** | Overdue now: issued actions open or in progress with today (tenant's zone) after their due date, by owner, with bands 1–30, 31–90 and over 90 days and how many were re-dated. Closed in the period: how many were done by their **original** date and by their **agreed** date. Only actions the viewer sees (E07's rule) |
| **M3 Time from submission to decision** | Decisions the viewer sees in full, on items placed from a submission, whose outcome is final (not deferred or referred): from the chain's **first** submission (so a deferral or a referral keeps counting) to the decision, in calendar days and in working days (the tenant's business calendar). Median and 90th percentile by nearest rank; fewer than 5 decisions shows "too few"; by quarter and by memo type |
| **M4 Items deferred** | Of those decisions, the deferred ones; and items deferred again (an earlier deferral in the same chain), once per chain |
| **M5 Papers on time** | Per held meeting: the **first** published revision at or before the papers deadline (working days before the meeting, on the meeting's date); never published is late; a meeting with no deadline is left out of the share. Late items and papers: Late or Tabled rows of the latest revision the viewer sees in full (officers and oversight only) |

Not measured, on purpose: paper opens and reading time, meeting length, number of items or pages, composite scores.

### When a number looks wrong

1. **Two people see different numbers.** Expected: each sees only what they may (clearance, recusals, restricted items, their committees). "Based on n decisions you can see in full" under M3 and M4 says how many decisions the figure stands on. Compare with a `forum.read` or `forums.manage` account, never by sharing a screen.
2. **A meeting is missing.** It is not held yet (mark it as held), it is above the viewer's clearance, or its date falls outside the period. The period ends today **in the tenant's zone**; a meeting is dated in **its own zone**. A tenant without `default.timezone` counts in UTC, so a meeting just held in Dhaka after 18:00 UTC is dated tomorrow and left out until the date changes: set the organisation's time zone (Organisation → calendar). The demo tenant is on Asia/Dhaka.
3. **Attendance is too low.** The register at the meeting was not kept (nobody marked present: everyone is absent), or a member was recorded only through their alternate. Correct the register ("Record a missed entry…", meetings-and-agendas.md); the figures follow at once. A term entered with the wrong dates changes "expected".
4. **Time to decision is too long.** The chain started at the first submission, which may be a deferral months ago; a referral counts from the first committee. Calendar days include weekends and holidays: "Count working days" shows both.
5. **Papers on time says late.** The first revision was published after the deadline, even if a later one was on time; a meeting moved earlier moves its deadline. A holiday added to the calendar after publication changes the deadline the measure computes.
6. **An overdue action is not counted.** It is a draft (not issued), the viewer cannot see it (another committee, a recused item), or it was re-dated.
7. **The register disagrees with analytics.** M3 and M4 read the register's rows: run the unindexed check. Analytics reads nothing else from the register.
8. **The browser's date.** The period picker's quick choices use the browser's date; the server's default period uses the tenant's. Near midnight they can differ by a day (a known gap, below).

## Rate limits

| Limit | Allowance | Key | Where |
| --- | --- | --- | --- |
| `DECISION_SEARCH_PER_USER` | 30 a minute | tenant and person | `POST /decision-register/search`, checked before the transaction (so a person without a register is limited too) |
| `REGISTER_EXPORT_PER_USER` | 5 an hour | tenant and person | the register's export, after the 403 and 422 checks |
| `ANALYTICS_EXPORT_PER_USER` | 5 an hour | tenant and person | the attendance export, after the 403 check |

Above the limit: 429 with `Retry-After`; the dialog says when to try again. Analytics reads have no limit of their own (they are not audited, and cost what the budgets below say). Each register search also has 2 seconds (503 `SEARCH_TIMEOUT`, as memo search). Someone harvesting by search: filter the audit on `search.decisions.performed` and the person; long runs of next pages are worth asking about.

## Monitoring

| Signal | Meaning | Action |
| --- | --- | --- |
| `memo.search.decisions.unindexed` above 0 | Rows the backfill could not write | The log line names the decision; see the unindexed check |
| `memo.jobs.failed` for `search-decisions-backfill` | A chunk failed five times | Read the log; schedule the backfill again once fixed |
| `search.decisions.backfilled` from a daily check (not after a deployment or a rebuild) | Rows were missing or behind: a change was not indexed in its transaction | Look at which decisions it wrote (their history in the minutes) and report the change that missed its row |
| 503 `SEARCH_TIMEOUT` on the register | A search over 2 s | Run `ANALYZE search_decision` as the schema owner; check the database memory (search.md, Sizing) |
| Many `search.decisions.exported` by one person | Exports beyond the 5 an hour are refused; within it, look at the filters | Ask why |
| The export's "Number check" not all accounted for | A gap or a duplicate in a committee's numbers | Incident (minutes-and-resolutions.md) |

**Performance** (E08 T5, about 55,000 decisions, quiet / loaded, the budget in brackets; the tests fail at twice the budget, the headroom for shared CI runners): the register newest first 22–33 / 41–106 ms (150 ms), one committee 16–24 ms, number order 13–17 ms (150 ms), words 19–44 / 40–113 ms (300 ms), a number 11–18 / 23–62 ms (50 ms); at 115,000 decisions within the same budgets. Analytics overview 94–145 ms for a secretary of three committees, 502 / 685–989 ms for 30 committees (800 ms; the loaded runs went over); a committee's detail 48–157 / 92–363 ms (500 ms); the attendance export 54–109 ms for 38 seat holders (3 s). Weekly `load` jobs `committee-volume` (`RegisterVolumeIT`, `AnalyticsVolumeIT` at 3,600 and 7,200 meetings) and `committee-load` (50 people for six minutes); run them by hand after a change to these queries: `gh workflow run load.yml --ref master`. The reports are the artefacts `committee-volume-report` and `committee-load-reports`.

## Known limits

- The web's defaults (the register's dates and file names, the analytics period's quick choices) use the browser's date; the server checks and defaults use the tenant's. Near midnight in another zone they can differ by a day.
- A word search matches at most 2,000 full rows before ranking (10,000 for an export); counts are exact up to 10,000 ("more than 10,000").
- The register has no filter for "decisions on a submission": the analytics link "Open these decisions" lists every decision of the committee and period.
- The number order needs one committee (422 `SORT_NEEDS_ONE_FORUM`).
- A member appointed after a meeting sees that meeting's decisions in the register (today's seat), though the meeting page does not show them that meeting.

## Demo data

The register holds the demo's decided items (E06 T12): BRC/…/001–003 at the Board Risk Committee's "Risk review, third quarter" (Demo Director recused from the Rupsha Steel item: a restricted line for him) and MCC/…/001 with a deferral and a decision to be ratified at the Management Credit Committee's "Monthly credit review". No demo account holds `forum.read`: to show the oversight view, grant a role with it to `auditor@demo.example`. A year of history for analytics is seeded with the phase's demo cycle (E08 T8). Seeders only fill gaps; the backfill indexes whatever they add.
