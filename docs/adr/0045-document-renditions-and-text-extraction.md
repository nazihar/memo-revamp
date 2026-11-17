---
status: accepted
date: 2026-11-14
deciders: Product owner (delegated gate, Phase 3 Q1), agent
---

# 0045 — Document renditions and text extraction: background jobs, ONLYOFFICE conversion, a separate Tika server

## Context and problem statement
Phase 3 needs a PDF rendition and the extracted text of every memo document and attachment version: PDF to preview attachments in the browser (E02) and to build the final archival rendition (E04); text to compare versions (E02) and to search (E05). Converting and parsing files that people upload is the riskiest thing the backend will do with content: office formats, PDFs and archives are a long history of parser exploits, decompression bombs and runaway CPU. Where does the work run, how is it scheduled, and where are the results kept?

## Decision drivers
- Hostile files must not reach a parser inside the backend JVM, which holds tenant keys and database connections (`AGENTS.md §3.4`; Tika's own guidance: "Never run Tika in the same JVM as … a service whose availability matters").
- Only services that run unmodified may be AGPL (ONLYOFFICE); libraries must be permissive (`§3.1`).
- No outbound network from converters; nothing fetched by URL.
- An upload must not wait for a conversion: renditions are derived, retried and may fail without failing the upload.
- Tenant isolation and encryption at rest apply to derived content as much as to originals.

## Considered options
1. **Background jobs; ONLYOFFICE's conversion API for PDF; a separate, unmodified Apache Tika server for text** (research §1.1, §3).
2. In-process: PDFBox and Tika parsers in the backend JVM; LibreOffice in a sidecar for PDF.
3. Synchronous conversion during upload.

## Decision outcome
Chosen option 1.
- **Model:** the files module owns `files_rendition` (per file version and kind `PDF` or `TEXT`): state `PENDING → DONE | FAILED | NOT_POSSIBLE`, attempts, error code, object key, size, SHA-256, page count, truncated flag. RLS forced. A purpose opts in with `FilePurpose.renditions` (none by default); the memo document and memo attachments opt in to both.
- **Scheduling:** a `FileVersionCreated` listener records `PENDING` rows in the upload's transaction and schedules a tenant one-off job (db-scheduler) after commit. A recovery job picks up `PENDING` rows older than five minutes. Retries with backoff for transient errors (at most four attempts), then `FAILED`. The job runs as the system actor in the tenant's context.
- **PDF:** through a `RenditionConverter` port declared by the files module and implemented by the `docedit` ONLYOFFICE adapter: `POST /converter/from-file`, async with a key derived by HMAC from (tenant, file version, output, parameter version), JWT with `operation: "converter"`. The backend streams the decrypted bytes over the internal network, so ONLYOFFICE never fetches anything. ONLYOFFICE errors -2 (timeout) and -10 (size) are final; -1, -3, -4 and -6 are retried. The rendition's page count is checked with the existing `PdfInspector` (limit: the purpose's `maxPdfPages`). A PDF original is its own preview (no rendition stored); images are previewed as images; other types are `NOT_POSSIBLE`.
- **Text:** through a `TextExtractor` port implemented by a client of `apache/tika` 4.2.0 (standard image, unmodified, Apache-2.0) on an internal network with no egress, reachable only from the backend: `PUT /tika/text`. Limits in its config: 5,000,000 characters (truncated beyond, marked), embedded depth 3 and count 500, zip-bomb ratio 100, task timeout 120 s, progress timeout 60 s, fork heap 1 GiB, per-request config and pipes off, no OCR or model parsers. 503, 429 and timeouts are retried.
- **Storage:** renditions are encrypted with the tenant's file key like originals (ADR-0028) and stored in the `work` bucket: they are derived and can be rebuilt, so they are not records. They are read only through the original's purpose rules (`mayRead` of the original file) and audited as `file.opened` with `rendition: PDF|TEXT`.
- **Capacity:** at most two conversions and two extractions at a time per backend node (settings), so editing sessions keep their share of the document server.

### Consequences
- Good: parsers run in two isolated, resource-limited containers with no network; a crash or a bomb costs a retry, never the backend.
- Good: one model serves preview (E02), comparison (E02), the final rendition (E04) and search (E05).
- Bad: one more service to run (Tika, about 1–1.5 GiB). On a small install it can be left out: text is then `NOT_POSSIBLE` and comparison and search say so.
- Bad: ONLYOFFICE CE is one instance, so conversions share it with editing; the concurrency cap and the job queue absorb bursts.
- Bad: spreadsheets have no ONLYOFFICE text output; Tika covers them.

### Confirmation
`RenditionIT` (job states, retries, `NOT_POSSIBLE`, tenancy, audit on read), the hostile-file corpus against both services in the E2E stack (nightly), and the T1 spikes recorded in the epic log.

## Pros and cons of the options
### Option 1 — background jobs, ONLYOFFICE conversion, Tika server
- Good: no parser in the backend; reuses a service we already run; Tika 4 forks a child per parse.
- Bad: a new container and an HTTP hop.
### Option 2 — in-process parsers, LibreOffice sidecar
- Good: fewer containers for text.
- Bad: PDFBox, POI and Tika parsers in the backend JVM are exactly the exposure the drivers rule out; LibreOffice is a second office engine to harden.
### Option 3 — synchronous conversion during upload
- Good: the rendition exists as soon as the upload ends.
- Bad: uploads take as long as the slowest conversion; a converter outage blocks uploads.

## More information
Research: `docs/plan/phase-3-memo-efile/epic-02-documents/research.md` §1, §3, §5. Related: ADR-0028 (encryption), ADR-0029 (upload pipeline), ADR-0037 (editor adapter), ADR-0047 (ONLYOFFICE 10 and hardening).
