#!/bin/sh
# Idempotent bucket bootstrap (ADR-0005). Runs with the S3 *admin* identity; the app identity never creates buckets.
#   records  object lock enabled at creation + versioning (record storage)
#   work     plain bucket (drafts, conversions, temporary artifacts)
# Required env: S3_ENDPOINT, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY.
set -eu
: "${S3_ENDPOINT:?}"
s3api() { aws --endpoint-url "$S3_ENDPOINT" s3api "$@"; }

if s3api head-bucket --bucket records >/dev/null 2>&1; then
  echo "records: exists"
else
  s3api create-bucket --bucket records --object-lock-enabled-for-bucket >/dev/null
  echo "records: created with object lock"
fi
s3api put-bucket-versioning --bucket records --versioning-configuration Status=Enabled

# Fail loudly if object lock is not actually on (e.g. bucket created by hand without it).
if ! s3api get-object-lock-configuration --bucket records --query 'ObjectLockConfiguration.ObjectLockEnabled' --output text | grep -q Enabled; then
  echo "records: object lock is NOT enabled; recreate the bucket" >&2
  exit 1
fi

if s3api head-bucket --bucket work >/dev/null 2>&1; then
  echo "work: exists"
else
  s3api create-bucket --bucket work >/dev/null
  echo "work: created"
fi
echo "storage-init: done"
