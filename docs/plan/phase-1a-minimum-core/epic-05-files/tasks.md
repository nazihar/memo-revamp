# E05 Files service — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Crypto and keysets:** add Tink; `KeyProvider` + `SoftwareKeyProvider` (HKDF label `memo/v1/kek`); `files` module skeleton; `files_tenant_keyset` table (RLS), create-on-first-use with `ON CONFLICT`, cache, `files.keyset.created` audit; `FileCipher` (encrypting channel to a file, seekable decrypting channel, AAD format) | M | — |
| T2 | **Object store:** `common.storage.ObjectStore` replaces `RecordStore` (audit anchors move over unchanged); streamed put with optional lock; `extendRetention`; `S3RangeChannel` (segment-aligned ranged reads, read-ahead); `delete` for `work` only; integration tests against SeaweedFS for multipart + lock headers and retention extension | M | — |
| T3 | **clamd:** `ClamdClient` (`INSTREAM`, `VERSION`, strict reply parser, timeouts); `deploy/clamav/clamd.env` (settings shared by Compose, Helm and tests); `TestMalwareScanner` Testcontainer with a test signature database; the health probe keeps its `PING` | M | — |
| T4 | **Content inspection:** add `tika-core`, `tika-parser-zip-commons`, PDFBox; `ContentInspector` (magic + zip containers), `OoxmlTypeReader`, `PdfInspector`, file-name sanitiser and extension map, inspection executor with timeout | M | T1 |
| T5 | **Upload pipeline and API:** `FilePurpose` registry, `files_file`/`files_version`/`files_pending_upload` tables (RLS, immutability trigger), single-pass pipeline (digest + scan + encrypt to spool), inspection, store, business transaction with `FileVersionCreated` and audit, rejections, rate limit `UPLOAD_PER_USER`, node semaphore, Idempotency-Key, `If-Match` on new versions; multipart disabled; spool start-up sweep; test purposes | L | T1–T4 |
| T6 | **Downloads:** content endpoints (GET/HEAD), range handling (`206`, `416`, multi-range → `200`, `If-Range`, `If-None-Match`), headers, open auditing with the session marker, integrity failure handling, `GET /files/{id}` with versions page | M | T5 |
| T7 | **Retention and sweeps:** provisional → final retention after commit; `files-retention-finalise` and `files-pending-sweep` jobs; metric and health detail for old provisional locks; tenant settings (`files.upload.max-bytes`, retention mode and years) | S | T5 |
| T8 | **Hostile-file corpus and fuzzing:** corpus resources (generated at test time where possible: zip bomb, nesting, huge-page PDF; EICAR built from parts so no scanner flags the repository); `HostileFileCorpusIT`; Jazzer dependency, `UploadPathFuzzTest` in the nightly stage | M | T5 |
| T9 | **Matrix, cross-tenant, performance:** `e05-files.yaml`; `FilesCrossTenantIT`; `FilesPerformanceIT` (budgets of design §11, nightly); OpenAPI drift and generated client | S | T6 |
| T10 | **Deployment and docs:** nginx upload route (streaming, cap), Caddy route (cap, no `encode`), Compose and Helm take `clamd.env`, dev environment turns on the `antivirus` profile, Helm optional ClamAV with NetworkPolicy, writable temp volume for the spool; on-prem runbook (spool disk, private signature mirror); `dependencies.md`; ASVS V5/V11 rows; STATUS | M | T5–T9 |

T1, T2 and T3 can run in parallel; T4 after T1 (inspection reads the spool through the decrypting channel); T6 and T7 in parallel after T5.

## Acceptance criteria (selection; full list in test-plan.md)
- **T1:** Given a tenant without a keyset, when two nodes encrypt at once, then exactly one keyset row exists and both ciphertexts decrypt. Given tenant A's ciphertext, tenant B's keyset cannot decrypt it. Given the stored keyset bytes, they do not parse without the KEK.
- **T2:** Given a record object stored with a provisional lock, when retention is extended, then `GetObjectRetention` shows the final date in the same mode; deleting that version, shortening or bypassing its lock, and changing GOVERNANCE to COMPLIANCE are refused, and an overwrite only adds a version. Given a 100 MiB object, a 64 KiB range at any offset reads at most two segments.
- **T3:** Given EICAR, clamd reports `FOUND`; given clean bytes, `OK`; given an unreachable or misbehaving clamd (garbage reply, early close, timeout), the client reports an error, never clean.
- **T4:** Given a DOCX, a DOCM renamed `.docx`, an ODT, a PDF and a ZIP, detection returns their true types without reading file names. Given a PDF with 100,000 pages or an encrypted PDF, inspection rejects it within its time and memory limits.
- **T5:**
  - Given an allowed PDF, when it is uploaded, then a version exists with the detected type, the SHA-256 of the plaintext, a random object key, and the stored object is ciphertext; `file.version.created` is audited in the same transaction.
  - Given EICAR, then `422 MALWARE`, nothing in either bucket, no version row, and `file.upload.rejected` with the signature name.
  - Given clamd down, then `503 SCAN_UNAVAILABLE` and nothing stored.
  - Given the same `Idempotency-Key` and content twice, then one version and the same response.
  - Given two concurrent new versions of one file with the same `If-Match`, then exactly one succeeds (`412` for the other) and version numbers stay gapless.
  - Given an upload in progress, then no database connection is held while the body is read.
- **T6:** Given a 1 MiB file, `Range: bytes=0-99` gives `206` with 100 bytes equal to the plaintext; `bytes=-100` the last 100; `bytes=2000000-` gives `416`; two ranges give `200` with the full body; a matching `If-Range` gives `206`, a stale one `200`. Given two downloads of the same version in one session within 30 minutes, then one `file.opened` entry.
- **T7:** Given a version whose retention extension failed, then the job finalises it within 5 minutes. Given a pending upload whose transaction never committed, then the sweep removes the `work` object after 1 hour and the `records` object after its provisional lock expires.
- **T8:** Every corpus file is rejected with its expected reason; heap growth during the corpus stays under 64 MiB; nothing reaches storage.
- **T10:** Given the production web image, a 50 MiB upload through nginx reaches the backend without nginx writing a temp file; other routes still refuse bodies over 1 MB.
