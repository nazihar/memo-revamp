# Test plan — E05 Files service

## Scope and risks
New: the `files` module (purposes, upload pipeline, versions, downloads), per-tenant keysets and the key provider, the object store with locks and ranged reads, the clamd client, content inspection, retention jobs, deployment of ClamAV. Highest risks: malware or active content stored and served, plaintext at rest, crypto mistakes in range reads, undeletable orphans or shortened retention, hostile files exhausting resources. See [design.md](design.md), [threat-model.md](threat-model.md).

## Test infrastructure
- **SeaweedFS:** the existing `TestObjectStorage` (same scripts as Compose).
- **ClamAV:** `TestMalwareScanner`: `clamav/clamav:1.5.4_base` with our `clamd.conf` and a test signature directory (`test.hdb`/`test.ndb` with EICAR and a test marker), no `freshclam`. Starts in seconds; heuristics work without the official database. The nightly stage also runs the corpus against the full-signature image.
- **Corpus:** `backend/src/test/resources/files/corpus/` holds small static samples (macro document, polyglot, malformed PDF). Large or dangerous samples are generated at test time (zip bomb, nested archives, 100,000-page PDF, oversized stream), and EICAR is assembled from two halves, so no scanner flags the repository and nothing large is committed.

## Tests per slice
| Slice | Layer | Test (behaviour) | CI stage |
|---|---|---|---|
| T1 | Unit / property | `FileCipherProperties` (jqwik: random sizes incl. 0, 1, segment ±1; random ranges equal plaintext slices), `EncryptionTamperTest` (bit flip, truncation, segment swap, AAD of another version or tenant), `SoftwareKeyProviderTest` (deterministic from the secret, distinct from `DataKeys`) | PR |
| T1 | Integration / concurrency | `KeysetStorageIT` (created once under two concurrent threads, cached, row unreadable without KEK, tenant B cannot unwrap A) | PR |
| T2 | Integration | `ObjectStoreIT` (put with lock headers, provisional → extended retention in each mode, delete/shorten/bypass refused in `records`, overwrite adds a version, mode change refused, delete allowed in `work`, expired provisional lock removable, ranged reads), `S3RangeChannelTest` (segment alignment, read-ahead, position/size semantics); existing `AuditAnchorIT` still green on `ObjectStore` | PR |
| T3 | Unit / integration | `ClamdReplyParserTest` (OK, FOUND, ERROR, garbage, empty, oversized reply), `ClamdClientIT` (EICAR, clean, stream limit, early close, timeout, unreachable) | PR |
| T4 | Unit / property | `ContentInspectorTest` (true type for each sample regardless of name), `OoxmlTypeReaderTest` (DOCX/XLSX/PPTX and macro variants; missing or huge `[Content_Types].xml`; DTD and external entities refused; entry and ratio limits), `PdfInspectorTest` (page count, encrypted, malformed, memory cap, timeout), `FileNameSanitiserProperties` (no path separators, controls or over-long names survive) | PR |
| T5 | Integration | `UploadIT` (happy path: version, digest, random key, ciphertext at rest, audit in the same transaction, `FileVersionCreated` veto rolls back and leaves only a pending row), `UploadRejectionIT` (each reason, nothing stored, audit), `ScannerDownIT`, `UploadIdempotencyIT`, `NewVersionConcurrencyIT` (latch, `412`, gapless numbers), `UploadRateLimitIT`, `UploadConcurrencyIT` (semaphore → `503` + `Retry-After`), `SpoolIsCiphertextIT`, `SpoolSweepIT`, `MultipartDisabledIT`, `NoTransactionWhileStreamingIT` | PR |
| T5 | Memory | `UploadMemoryIT` (100 MiB upload; heap growth ≤ 16 MiB) | PR |
| T6 | Integration | `DownloadRangeIT` (first, middle, last, suffix, open-ended, unsatisfiable, multi-range, `If-Range` strong match and mismatch, `If-None-Match`, `HEAD`), `DownloadHeadersIT` (disposition, inline only for allowed types, `nosniff`, CSP sandbox, CORP, cache headers), `OpenAuditIT` (once per session per version in 30 min, another session audits again, non-audited purpose never), `IntegrityFailureIT` (tampered object → aborted response, audited) | PR |
| T7 | Integration / job | `RetentionFinaliseIT` (failed extension retried by the job; idempotent), `PendingSweepIT` (work object deleted after 1 h; records object after provisional expiry; committed uploads untouched), `FilesSettingsIT` (tenant limit only lowers the ceiling) | PR |
| T8 | Security | `HostileFileCorpusIT`: EICAR, EICAR in zip, nested zip (12 levels), zip bomb (1 GiB of zeros in a few KiB), polyglot PDF/ZIP, HTML named `.png`, PDF named `.docx`, DOCM renamed `.docx`, legacy `.doc` with VBA, encrypted PDF, encrypted zip, malformed PDF, 100,000-page PDF, oversized stream, empty file; each → expected reason, nothing stored, heap bounded | PR (test signatures), nightly (full signatures) |
| T8 | Fuzz | Jazzer `UploadPathFuzzTest`: `ContentInspector`, `OoxmlTypeReader`, `ClamdReplyParser`, `RangeHeaderParser`, `ContentDispositionParser`; 10 minutes each, findings fail the job | nightly |
| all | Audit | `FilesAuditIT`: every state-changing endpoint emits its event (`assertAudited`); rejections and opens audited; keyset creation audited without key material | PR |
| all | Cross-tenant | `FilesCrossTenantIT`: tenant A's files, versions, content and keyset invisible from B (`404`), A's ciphertext moved under B's ids does not decrypt | PR |
| all | RLS | RLS meta-test covers the four new tables automatically | PR |
| T9 | Authorisation | `AuthorizationMatrixTest` with `e05-files.yaml`; `EndpointCoverageTest` | PR |
| T9 | Contract | OpenAPI drift; generated client compiles | PR |
| T9 | Performance | `FilesPerformanceIT` (design §11 budgets: 10 MiB upload, metadata p95, first byte, 64 KiB range in 100 MiB) | nightly |
| T10 | Image / deployment | web image test: upload route accepts 50 MiB streamed and other routes still refuse > 1 MB; on-prem smoke uploads and downloads a file through nginx with ClamAV on; Helm lint + kubeconform with `malwareScanner.deploy` on and off | PR (image), nightly (on-prem smoke, kind) |

## Authorisation matrix tests
`e05-files.yaml`: every endpoint × {anonymous, partial, user, orgAdmin, branchAdmin, auditor, tenantAdmin, deactivated session, other-tenant admin} → allow / 401 / 403 / 404, using the `test.document` purpose (upload needs `org.manage`, reads open to signed-in users) and seeded files in each tenant. Sample bodies are small clean PDFs; uploads carry `Idempotency-Key` and `Content-Disposition`.

## State-machine tests
- Version lock: NONE (ordinary), PROVISIONAL → FINAL (allowed); FINAL → PROVISIONAL, any change of other columns, deleting a version (forbidden; trigger and test).
- Upload: every rejection state leaves no version and no object; failure after STORED leaves exactly one pending row that the sweep resolves.

## Seed data
Two tenants from the existing fixtures; builders `aPurpose(...)`, `anUpload(purpose, bytes, name)`, `aStoredFile(tenant)`; the Demo Bank gets no files in E05 (its logo arrives in E07).

## Security tests
STRIDE → tests per threat-model.md. ASVS rows updated: V5 (file handling), V11 (cryptography, L3), V14 (data protection), V16 (audit). ZAP baseline on dev keeps running; the new endpoints need a session, so they are covered by the integration tests, not ZAP.

## Accessibility and i18n
No screens in E05. Problem Details `reason` codes are stable keys for E07's upload component messages (English and Arabic).

## Exploratory charters
1. Try to get active content (HTML, SVG, script in PDF) to render on the app origin through upload and download (45 min).
2. Try to leave plaintext on any disk: kill the backend mid-upload, fill the spool, stall the client, pull the network to clamd (45 min).
3. Try to make a record version disappear or change: retries, concurrent versions, crashes between storage and commit (30 min).

## Exit checklist
- [x] All listed tests implemented and green in their stage. Several were folded into fewer classes: tamper and range properties in `FileCipherProperties`; keyset races in `KeysetStorageIT`; storage and locks in `ObjectStoreIT`; upload rejections, idempotency, concurrency, veto, rate limit, spool in `UploadIT`; API, multipart, cross-tenant in `UploadApiIT`; ranges, headers, open audit, tampering and version shadowing in `DownloadIT`; jobs and settings in `FilesMaintenanceIT`; performance in `FilesPerformanceIT` (PR stage with 2x headroom, like the org performance test)
- [x] No new quarantined tests
- [x] ASVS checklist updated (V5, V11, V14, V16 rows)
- [ ] Corpus against the full official signatures: **not built yet** (the nightly runs the corpus with the test signatures, like the PR stage). Follow-up: a nightly job with `clamav/clamav:1.5.4` and a cached signature volume
- [ ] Dev VM runs ClamAV; memory after a day of signature reloads recorded in the log (after the first deploy)
