#!/bin/sh
# Single-process SeaweedFS for local development (ADR-0005): master + volume + filer + S3 gateway.
# Writes the S3 identities file from env so that no credential is committed.
set -eu
: "${S3_ADMIN_ACCESS_KEY:?}" "${S3_ADMIN_SECRET_KEY:?}" "${S3_APP_ACCESS_KEY:?}" "${S3_APP_SECRET_KEY:?}"

umask 077
cat > /tmp/s3.json <<EOF
{
  "identities": [
    {
      "name": "admin",
      "credentials": [{ "accessKey": "${S3_ADMIN_ACCESS_KEY}", "secretKey": "${S3_ADMIN_SECRET_KEY}" }],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    },
    {
      "name": "app",
      "credentials": [{ "accessKey": "${S3_APP_ACCESS_KEY}", "secretKey": "${S3_APP_SECRET_KEY}" }],
      "actions": ["Read:records", "Write:records", "List:records", "Read:work", "Write:work", "List:work"]
    }
  ]
}
EOF

exec weed server -dir=/data -ip.bind=0.0.0.0 -s3 -s3.port=8333 -s3.config=/tmp/s3.json -volume.max=0 -master.volumeSizeLimitMB=1024
