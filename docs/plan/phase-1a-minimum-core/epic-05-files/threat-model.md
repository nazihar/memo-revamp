# E05 Files service — threat model (STRIDE)

## Data flows
1. Browser / client → edge (Caddy) → web (nginx) → backend: upload body, download with ranges.
2. Backend ↔ clamd: `INSTREAM` over TCP on the internal network.
3. Backend ↔ local spool directory: ciphertext only.
4. Backend ↔ SeaweedFS (S3): ciphertext objects, retention calls, ranged reads.
5. Backend ↔ PostgreSQL: file metadata, wrapped keysets, pending uploads, audit.
6. Backend ↔ key provider: KEK (software in 1a, derived from `MEMO_DATA_KEY`).

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| S1 | A request uploads or reads as another user or tenant | 1 | Session authentication; tenant from host (ADR-0022); file ids loaded under RLS; purpose callbacks call `Policy` | V8.2.x, V8.4.1 | matrix `e05-files.yaml`, `FilesCrossTenantIT` |
| T1 | Malware stored and later downloaded by colleagues | 1, 2 | clamd scan of every byte before storage; heuristics on (limits, encrypted, macros, broken executables); fail closed when the scanner is down; rejected files never stored | V5.2.x (file upload) | corpus: EICAR, EICAR in zip, nested zip, macro doc → `MALWARE`; `ScannerDownIT` → `503`, nothing stored |
| T2 | A stored object is altered or replaced in object storage | 4 | Tink authenticates every segment; AAD binds tenant/file/version; record bucket object-locked (`app` identity cannot bypass or shorten); reads and retention changes address the storage version recorded at upload, so a newer version under the same key never shadows a record; download aborts on failure and audits it | V11.3.x, V16.x | `FileCipherProperties` (flip, truncate, reorder, move), `ObjectStoreIT` (delete/shorten/bypass refused), `DownloadIT` (shadowing version ignored, tampered content never served and audited) |
| T3 | Type confusion: an HTML or SVG file served as an image runs script on our origin; a polyglot passes as PDF | 1 | Type detected from content; extension must agree; SVG and HTML never allowed; downloads with `nosniff`, `attachment` unless the purpose allows inline for safe types, `CSP: default-src 'none'; sandbox`, `CORP: same-origin` | V5.2.2, V5.3.x, V3.4.x | corpus: polyglot, wrong extension, HTML named `.png`; `DownloadHeadersIT` |
| T4 | Macro-enabled Office document accepted | 1 | OOXML content types read (macro-enabled variants detected and not on any allow-list); OLE2 formats not allowed in 1a; clamd `AlertOLE2Macros` | V5.2.x | corpus: `.docm`, `.docm` renamed `.docx`, `.doc` with VBA |
| T5 | Retention shortened or record deleted by the application or an operator | 4 | Object lock (GOVERNANCE default, COMPLIANCE optional) on every record version; the `app` identity has no bypass or admin right; versions append-only by trigger | V16.x | `ObjectLockIT`, `VersionImmutabilityIT` |
| R1 | A user denies having uploaded or opened a document | 1, 5 | `file.version.created` and `file.opened` in the hash-chained audit, with session, IP and user agent (ADR-0019); rejections audited | V16.3.x | `FilesAuditIT` |
| I1 | Plaintext left on disk (proxy buffers, servlet multipart temp files, PDFBox scratch, spool) | 1, 3 | Raw body, multipart disabled; nginx `proxy_request_buffering off` on the upload route; spool is ciphertext (`0600` in a `0700` directory, removed in `finally` and at start); PDFBox memory-only | V14.2.x (data protection) | `SpoolIsCiphertextIT` (spool bytes never contain a plaintext marker), `MultipartDisabledIT`, nginx config check in the image test |
| I2 | Keys leak: tenant keyset readable, KEK in logs or config | 5, 6 | Keyset stored encrypted under the KEK with tenant AAD; never logged or audited; KEK derived in memory from the existing secret; `toString` redaction | V11.2.x, V13.3.x | `KeysetStorageIT` (row does not parse without the KEK; tenant B cannot unwrap A's), log redaction test |
| I3 | Sensitive data in URLs, names or logs | 1 | Original name only in a request header and the database; object keys random; logs carry ids only | V14.x | `UploadLoggingTest` (no name or content in logs) |
| I4 | Existence of another tenant's file leaks through `403` vs `404` | 1, 5 | RLS first: absent → `404` | V8.4.x | matrix (`otherTenantAdmin`) |
| D1 | Decompression bomb, deeply nested archive, huge PDF exhaust memory or CPU | 1–3 | Size cap while streaming; clamd limits with `AlertExceedsMax` (nesting; clamd only trims a single oversized entry, see research §R15); our zip reader refuses bombs from the central directory (entries, per-entry ratio, total declared size); PDFBox memory cap and page limit; inspection on a bounded executor with a timeout | V5.2.3, V5.2.4 | corpus: zip bomb, 42.zip-style nesting, 100k-page PDF, oversized; `UploadMemoryIT` (heap delta for 100 MiB) |
| D2 | Upload flood fills spool disk or saturates clamd | 1–3 | Rate limit per user; per-node semaphore (`503` + `Retry-After`); spool bounded by ceiling × permits | V2.4.x | `UploadRateLimitIT`, `UploadConcurrencyIT` |
| D3 | Slow upload holds a thread and a database connection | 1, 5 | No transaction open while reading the body or streaming the download; servlet read timeout; edge timeouts | V2.4.x | `NoTransactionWhileStreamingIT` (pool usage during a stalled stream) |
| E1 | Path traversal or object-key injection through the file name | 1, 4 | Name stripped of path parts and controls; never used in object keys or paths | V5.3.x | `FileNameSanitiserTest` (property) |
| E2 | A parser bug in detection, the OOXML reader, the clamd reply parser or the `Range` parser is exploitable | 1, 2 | Small, strict parsers; Jazzer nightly fuzzing; Tika limited to detection, no full parsers | V1.x | Jazzer `UploadPathFuzzTest` (nightly) |

## Residual risks
- In 1a the KEK derives from `MEMO_DATA_KEY`: whoever holds that secret and a database copy can decrypt every tenant's files on the installation. 1b moves the KEK into a KMS/HSM (ADR-0028).
- Signatures are only as fresh as `freshclam`; air-gapped installs depend on the customer's mirror. Stored files are not re-scanned when signatures update (Phase 3).
- Legacy OLE2 Office formats are refused in 1a rather than inspected; Phase 2 must decide before memo attachments allow them.
- Declared sizes in a zip can lie; nothing in 1a extracts archives except clamd (bounded) and our content-types read (bounded). Any later extractor (text extraction, conversion) must enforce its own limits.
- clamd speaks no TLS: upload content crosses the internal network to the scanner unencrypted (Compose network, Kubernetes NetworkPolicy keep it internal). Installs that need encryption on that hop run clamd beside the backend (same pod or host) or behind a TLS tunnel.
- Range reads are not audited individually (open granularity by design, AGENTS §3.3).
