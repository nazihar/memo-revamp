# Search, following and the team view

**What they are:**
- **Search** (ADR-0054) finds memos by their details, numbers, notes and the text of their documents and attachments. It shows only what the reader may open.
  - **Where it runs:** search tables of its own in PostgreSQL (`search_memo`, `search_note`, `search_chunk`), behind SECURITY DEFINER functions that apply the read rule at query time. Access is never stored in the index.
  - **Access:** the application role holds nothing on those tables.
  - **Audit:** every search is audited with its words and filters (`search.performed`); application logs never carry the words.
  - **Privacy:** search words never travel in a URL. The API takes them in a POST body, and the Search page keeps them in the address fragment (`/search#q=…`), which browsers do not send.
- **Following** (design E05 §4) tells people about memos they follow:
  - in the app, once a memo has been quiet for 3 minutes (all its news in one item);
  - by email, as each person chooses: a daily digest at 08:00 in the tenant's time zone (the default), at once, or never. Emails carry the memo's number and a link, never its subject; the digest carries only a count.
- **The team view and the approvals dashboard** show the open work of the people in the units a person oversees: heads of a unit for its subtree, and holders of `inbox.team.read` or `memo.dashboard.read` for their grant's scope.

## What must be running
| Component | Why | Without it |
|---|---|---|
| The scheduler (`MEMO_JOBS_ENABLED=true`) | `search-chunks` (a document's text into the index), `search-backfill` (swept hourly until every memo is indexed), `memo-watch-flush` (every minute), `memo-watch-digest` (checked hourly, sends at 08:00), `memo-watch-sweep` (daily: drops follows of memos the person may no longer read) | Details and notes are still indexed with each change. Documents' text and followers' news wait; nothing is lost |
| Apache Tika (`MEMO_TIKA_URL`) | Extracts the text that `search-chunks` indexes | Documents are not searchable by their words (details and notes are) |
| The mail relay | Digests and "at once" emails | In-app news still arrives; emails wait in the outbox |

## Sizing
- **Measured (E05 T1, the V1 volume):** at 1,000,000 memos the search p95 is within 300 ms for every reader profile, but only with the database sized for a 16 GB VM:
  `shared_buffers=3GB`, `effective_cache_size=8GB`, `work_mem=32MB`, `random_page_cost=1.1`.
- **With PostgreSQL's defaults** the same volume took up to 897 ms (a unit head's phrase search).
- **Rule:** past about 100,000 memos, set the database memory in the on-prem `.env` (`MEMO_PG_*`, see `deploy/compose/onprem/.env.example`) to the V1 values and give the container 6 GB (`MEMO_PG_MEMORY_LIMIT=6g`). Restart PostgreSQL to apply. For Kubernetes, set the same parameters on the managed database.
- **Weekly checks:** the `load` workflow runs `SearchPerformanceIT` at 1,000,000 memos with these settings, and Gatling on volume V2 (search, the inbox by due time, the team view and the dashboards). Their reports are kept for 30 days.

## Everyday tasks
**A memo is not found by a word in its document.**
1. Check that `MEMO_TIKA_URL` is set and that Tika answers (`curl <MEMO_TIKA_URL>/version` from the backend's host).
2. Check the document's text rendition: a `files_rendition` row of kind `TEXT` for the current version, in state `DONE`. If it failed, retry the rendition (attachments-and-previews runbook).
3. Check the `search-chunks` job for that version in the scheduler table. A failed job retries; one that keeps failing logs the version id.
4. Words inside hyphenated file names (`otter-notes.docx`) are one token for PostgreSQL. Search the words of the text, not of the file name.
5. The reader may not be cleared for the attachment: its words then find nothing for them, by design.

**Searches answer "That search took too long" (503 `SEARCH_TIMEOUT`).**
- Each search has 2 seconds.
- **Phrases of very common words** are the slowest. Users narrow them with filters.
- **Many timeouts:**
  1. Check the database memory settings (Sizing above).
  2. Run `ANALYZE search_memo, search_note, search_chunk` as the schema owner.
  3. See ADR-0054's triggers for moving to OpenSearch.

**Someone may be harvesting by search** (threat model D2).
- Each person may search 30 times a minute (429 above), and every search is audited.
- In Administration → Audit, filter on `search.performed` and the person. Many distinct searches, or long runs of "Load more", in a short time are worth asking about. The audit shows the words; treat the export as being as confidential as the memos.

**Rebuild a tenant's search index** (after a PostgreSQL major upgrade, or if the index is suspected wrong):
1. As the schema owner, delete the tenant's rows (the owner is not bound by the search tables' row security):
   ```sql
   delete from search_chunk where tenant_id = '<tenant id>';
   delete from search_note  where tenant_id = '<tenant id>';
   delete from search_memo  where tenant_id = '<tenant id>';
   ```
2. The next `search-backfill` run (hourly, or run it from the scheduler table) re-indexes every memo and its notes in batches of 200, and schedules the text of each document and attachment.
3. Until it finishes, memos not yet re-indexed are not found. Watch `search-backfill` until it finds nothing more to index.

**PostgreSQL major upgrade (for example to 19).** The English stemmer and character classes may change, so stored vectors must be recomputed. After `pg_upgrade`:
1. Rebuild every tenant's index as above.
2. `REINDEX` the GIN indexes `search_memo_tsv_idx`, `search_note_tsv_idx` and `search_chunk_tsv_idx`.

**Someone gets no digest.**
1. Check their choice: the notification preferences page, "Memos you follow" (`GET /api/v1/me/watch-settings` as them).
2. Check that they follow memos with news since their last digest.
3. Check the mail outbox for `WATCH_DIGEST`.
- Digests go at 08:00 in the tenant's time zone (Organisation → calendar).

**A follow disappeared.** The daily sweep drops follows of memos the person may no longer read (a share ended, a clearance lowered). That is intended.

**A head does not see their team.**
- The team view takes the units at and below the head position the person holds today.
- Check the person's assignment, and that the position is marked as the unit's head (Organisation).
- Others need `inbox.team.read` (team view) or `memo.dashboard.read` (dashboard) granted on purpose, scoped to a unit.
- Memos the head may not read show as "Restricted memo", with no subject or number. To read them, the head needs `memo.read.unit` on the unit, granted on purpose.

## Limits
- **Search:** at most 200 characters and 12 words; a word ending a search is also matched as a prefix when it has 3 or more characters; 30 searches a minute per person (429 above).
- **Counts:** exact up to 2,000 matches ("2,000+" above). Suggestions look at 200 candidates.
- **Documents:** each file version is indexed in chunks of at most 2,000 words, at most 500 chunks.
- **Saved searches:** at most 50 per person; private; they keep criteria, never results.
- **Bulk actions:** Watch, Unwatch, Claim, Release and Reassign (Reassign for heads, with a step-up); at most 100 items, each done as if alone; decisions are never accepted in bulk.
