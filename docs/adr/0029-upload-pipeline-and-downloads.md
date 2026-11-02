---
status: accepted
date: 2026-11-02
deciders: product owner (delegated), agent
---

# 0029 — Upload pipeline (detection, scanning, limits) and streamed range downloads

## Context and problem statement
AGENTS §3.4 requires every upload to be size-limited, type-detected by content, malware-scanned **before** encryption, stored under a random key and never served without `Content-Disposition` and strict headers. It also requires hostile-file limits wherever a file is parsed. AGENTS §2 requires downloads to stream through the backend with HTTP range support and no presigned URLs. Objects in the locked `records` bucket cannot be deleted, so nothing unaccepted may land there. Research: `epic-05-files/research.md §R15` and "Range requests".

## Decision drivers
- No plaintext at rest anywhere, including proxy and servlet temp files.
- A file is read once from the client; memory stays constant for any size.
- A file that cannot be scanned or understood is rejected, never stored with a warning.
- No orphaned, undeletable objects in `records`.
- One protocol that works the same on SaaS, dedicated and on-prem installs.

## Considered options
1. **Single pass to an encrypted local spool**, inspect by decrypting the spool, upload the ciphertext once accepted
2. Stream encrypted to a `work` staging object while scanning, server-side copy to `records` on success
3. Buffer the whole file in memory, then scan, encrypt and upload

## Decision outcome
Chosen option: **1**.

**Request format.** The body is the raw file (`Content-Type: application/octet-stream`); the original name travels in `Content-Disposition: attachment; filename*=UTF-8''…` on the request, never in the URL. Spring multipart is disabled for the whole application. The web image's nginx streams the upload route (`proxy_request_buffering off`, body cap at the platform ceiling), and the edge allows the same cap on that route only.

**Purposes.** Every upload names a purpose registered in code by the module that owns the files (`FilePurpose`): allowed media types, maximum size, PDF page limit, storage class (`RECORD` → `records` bucket, object-locked; `ORDINARY` → `work`), retention, whether reads are audited, and an access callback deciding who may upload and read. No purpose, no upload.

**Pipeline** (one request, bounded by a per-node semaphore and a per-user rate limit):
1. Check purpose, declared length (if any) and limits before reading the body.
2. One read loop; each chunk goes to SHA-256, to clamd `INSTREAM` and into the Tink encrypting channel (ADR-0028) writing a spool file in a private temp directory. Exceeding the size limit aborts at once.
3. clamd verdict: `OK` continues; `FOUND` (signatures and heuristics such as `Heuristics.Limits.Exceeded`, `Heuristics.Encrypted.*`, `Heuristics.OLE2.ContainsMacros`) rejects as `MALWARE`. A scanner error or timeout rejects as `SCAN_UNAVAILABLE` (`503`). **Fail closed**: there is no switch to skip scanning.
4. Detect the type from content: Tika magic on the first bytes; zip containers through `tika-parser-zip-commons`; OOXML through our `[Content_Types].xml` reader (macro-enabled variants detected); PDFs opened with PDFBox through the decrypting channel with a memory-only cap. Reject `TYPE_NOT_ALLOWED`, `TYPE_MISMATCH` (extension disagrees), `MALFORMED`, `ENCRYPTED`, `LIMIT_EXCEEDED`.
5. Accepted: record a pending upload (own transaction), upload the ciphertext from the spool to the purpose's bucket under `t/<tenantId>/<random 128-bit>`; record objects get a **provisional 7-day retention in the tenant's final mode** (the application identity cannot change a lock's mode later, only extend its date; verified against SeaweedFS).
6. Business transaction: insert the file version, delete the pending row, audit `file.version.created`. Then extend the record object's retention to its final date; a job retries any version whose lock is still provisional.
7. The spool is deleted in `finally`; a startup sweep removes leftovers. Every rejection is audited (`file.upload.rejected` with reason and, for malware, the signature name) and returns `422` Problem Details with a `reason`.

**Downloads.** `GET …/content` checks access through the purpose callback in a short transaction (and audits the open), then streams **outside** the transaction:
- single `Range` → `206` with `Content-Range`; multiple ranges → full `200`; unsatisfiable → `416`;
- strong `ETag` (version id and SHA-256), `If-Range`, `If-None-Match`; `Accept-Ranges: bytes`;
- `Content-Type` is the detected type; `Content-Disposition: attachment` except for purposes that allow inline display of safe types (PDF, PNG, JPEG, WebP);
- `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`, `Cross-Origin-Resource-Policy: same-origin`, `Cache-Control: private, no-store` (public purposes such as the tenant logo use `public, max-age=300`);
- excluded from compression at the edge.

**Open auditing.** The first request for a version in a session within 30 minutes records `file.opened`; further range requests in that window do not. The marker lives in the server-side session (Spring Session JDBC), so it holds across nodes and cannot be reset by the client. Purposes without read auditing (tenant logo) skip it.

### Consequences
- Good: nothing rejected reaches object storage; no plaintext on any disk; the client body is read once.
- Good: limits sit at every parser: clamd limits with `AlertExceedsMax`, our zip reader, PDFBox memory cap, executor timeouts.
- Bad: each node needs local disk for spools (maximum upload size × concurrent uploads, 800 MiB at the defaults). Documented in the deployment runbook.
- Bad: legacy OLE2 Office files (`.doc`, `.xls`, `.ppt`) cannot be told apart or checked for macros without Apache POI, so no 1a purpose allows them. Phase 2 decides for memo attachments.
- Bad: the request body is not multipart, so the web upload uses `XMLHttpRequest` for progress (E07's upload component). The generated client still owns every other call.
- Follow-ups: text extraction and conversion at upload time (Phase 2/3 with ONLYOFFICE), virus re-scan of stored files when signatures update (Phase 3), multi-range responses if the PDF viewer needs them (Phase 5).

### Confirmation
- Hostile-file corpus suite (EICAR, zip bomb, nested archive, polyglot, wrong extension, oversized, malformed PDF, huge page count, encrypted PDF, macro document) against ClamAV and SeaweedFS Testcontainers: each is rejected with its reason, nothing reaches the buckets, memory stays bounded.
- Range download tests (first, middle, last, suffix, unsatisfiable, multi-range, `If-Range` match and mismatch, segment boundaries).
- Object-lock tests: deleting a record version, shortening or bypassing its lock are refused (an overwrite only adds a version); provisional retention is extended after commit and by the retry job.
- A nightly Jazzer fuzz target over the detection, the OOXML reader, the clamd reply parser and the `Range` parser.

## Pros and cons of the options
### Single pass to an encrypted spool
- Good, because the object is written once, to its final place, only after acceptance.
- Bad, because it needs node-local disk for ciphertext spools.

### Staging object and server-side copy
- Good, because no local disk is needed.
- Bad, because SeaweedFS does not document CopyObject with object lock headers, and staging objects need their own clean-up.

### In-memory buffering
- Good, because it is the simplest.
- Bad, because heap use grows with file size and concurrency; it breaks "stream files".

## More information
- https://www.rfc-editor.org/rfc/rfc9110#name-range-requests (accessed 2026-11-02)
- https://raw.githubusercontent.com/Cisco-Talos/clamav/clamav-1.5.4/etc/clamd.conf.sample (accessed 2026-11-02)
- https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_request_buffering (accessed 2026-11-02)
- https://github.com/seaweedfs/seaweedfs/wiki/S3-Object-Lock-and-Retention (accessed 2026-11-02)
