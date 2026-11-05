---
status: accepted
date: 2026-11-02
deciders: product owner (delegated), agent
---

# 0028 — Envelope encryption of files: Tink streaming AEAD with per-tenant keysets

## Context and problem statement
AGENTS §3.4 requires per-tenant envelope encryption of documents, with the tenant data key wrapped by a key-encryption key (KEK) held in a KMS/HSM. The master plan accepts a software KEK in 1a and a KMS/HSM provider in 1b. Downloads must support HTTP range requests (AGENTS §2), so a byte range must be decryptable without reading the object from the start. Overview risk R4 asks for a vetted library, not our own format. Research: `epic-05-files/research.md §R14`.

## Decision drivers
- Authenticated encryption that detects tampering, truncation and reordering.
- Random-access decryption for ranges; constant memory for any file size.
- One tenant key for many files without nonce-reuse risk.
- A key-provider seam that a KMS/HSM can fill in 1b without re-encrypting files.
- No new secret to manage in 1a.

## Considered options
1. Google Tink `StreamingAead` (AES-GCM-HKDF) with per-tenant keysets wrapped by a KEK `Aead`
2. Our own segmented AES-GCM format on Bouncy Castle
3. AWS Encryption SDK for Java with a raw AES keyring

## Decision outcome
Chosen option: **1**.
- **Key hierarchy:**
  - **KEK** from a `KeyProvider` (`common.crypto`): `kek(id): Aead` plus `currentKekId()`. The 1a `SoftwareKeyProvider` derives an AES-256-GCM key with HKDF-SHA256 from `MEMO_DATA_KEY` (the secret that already protects TOTP secrets, ADR-0024) with the label `memo/v1/kek`, so the two uses never share a key. 1b adds PKCS#11 and OpenBao Transit providers.
  - **Tenant keyset:** a Tink keyset of `AesGcmHkdfStreaming` keys (AES-256 key, AES-256 derived keys, HKDF-SHA256, **64 KiB ciphertext segments**), created on first use. It is stored encrypted under the KEK with `TinkProtoKeysetFormat.serializeEncryptedKeyset`, the tenant id as associated data, in `files_tenant_keyset` with the KEK id.
  - **Per file:** Tink derives a fresh key from the keyset key and a random salt in each ciphertext header.
- **Associated data** of every file ciphertext: `memo/file/v1/<tenantId>/<fileId>/<versionId>`. A ciphertext moved to another tenant, file or version does not decrypt.
- **Rotation:** adding a new primary key to a tenant keyset affects only new files (the key id is in the ciphertext prefix). Re-wrapping a keyset under a new KEK rewrites one row. Rotation endpoints come with the 1b KMS work; the data model supports them now.
- **Caching:** unwrapped keysets are cached in memory per tenant for 10 minutes (Caffeine, evicted on keyset change through ADR-0020). File content is never cached.
- **Range reads:** `newSeekableDecryptingChannel` over a `SeekableByteChannel` that reads the ciphertext from object storage with ranged `GetObject` calls aligned to segments.

### Consequences
- Good: a published, vetted format; truncation and reordering are detected by design; range reads cost at most two segments (128 KiB) of extra reads.
- Good: KMS/HSM in 1b only wraps a few bytes per tenant; files are never re-encrypted when the KEK changes.
- Bad: adds `protobuf-java` to the classpath (BSD-3, widely used, scanned like everything else).
- Bad: a compromised `MEMO_DATA_KEY` in 1a exposes every tenant keyset on that installation. That is the accepted 1a risk; 1b moves the KEK into a KMS/HSM.
- Follow-ups: KEK rotation and keyset rotation endpoints (1b); crypto-shredding of a tenant (delete its keyset) for tenant off-boarding (Phase 7).

### Confirmation
- Round-trip and range property tests (random sizes and ranges, segment boundaries, empty and one-byte files).
- Tamper tests: a flipped byte, a truncated object, swapped segments and a ciphertext moved to another version id all fail to decrypt.
- A cross-tenant test: tenant B's keyset cannot open tenant A's ciphertext.
- A test that the stored keyset row contains no plaintext key material (it parses only with the KEK).

## Pros and cons of the options
### Tink streaming AEAD
- Good, because the seekable decrypting channel is exactly the range-read primitive we need.
- Good, because keysets, rotation and encrypted keyset storage come with the library.
- Bad, because the 64 KiB custom segment size is not one of the named templates (it is a supported parameter, verified in 1.24.0).

### Own segmented AES-GCM on Bouncy Castle
- Good, because there is no new dependency.
- Bad, because nonce derivation, last-segment marking and header design would be ours: the crypto risk R4 warns about.

### AWS Encryption SDK
- Good, because it is mature and keyring-based.
- Bad, because there is no random-access decryption: every range request decrypts from byte 0.

## More information
- https://developers.google.com/tink/streaming-aead (accessed 2026-11-02)
- https://developers.google.com/tink/wire-format (accessed 2026-11-02)
- https://github.com/tink-crypto/tink-java (accessed 2026-11-02)
