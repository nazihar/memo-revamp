---
status: accepted (dev topology). Production topology added in E05.
date: 2026-10-14
deciders: product owner (delegated), agent
---

# 0005 — SeaweedFS dev topology and object-lock bucket bootstrap

## Context and problem statement
Record buckets need S3 Object Lock, and that can be enabled **only when a bucket is created**. The application must not hold bucket-admin rights.

## Considered options
1. A one-shot init container with `amazon/aws-cli` that runs the documented `s3api` commands
2. The backend creates the buckets at startup
3. `weed shell` commands

## Decision outcome
Chosen option: **1**.
- **Dev topology:** a single `weed server -s3` container.
- **Bootstrap:** `storage-init` (aws-cli, pinned) is idempotent. It:
  - creates `records` with `--object-lock-enabled-for-bucket`;
  - enables versioning on `records`;
  - creates `work`.
- **S3 identities:**
  - `admin`: used only by `storage-init`;
  - `app`: object read/write on both buckets, no bucket administration.
- E05 reuses the same script as a Helm Job.

### Consequences
- Good: the app gets least privilege, and we follow the documented, supported path.
- Bad: one more image (about 150 MB) in the local stack.

### Confirmation
An integration test reads back the object lock configuration of `records`.

## More information
- https://github.com/seaweedfs/seaweedfs/wiki/S3-Object-Lock-and-Retention (accessed 2026-10-14)
