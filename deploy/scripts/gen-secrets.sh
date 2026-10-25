#!/bin/sh
# Fills every empty secret in an on-prem .env file with a fresh random value; never overwrites an existing value.
#   deploy/scripts/gen-secrets.sh /opt/memo/dev/.env
set -eu
file=${1:?usage: gen-secrets.sh <env-file>}
[ -f "$file" ] || { echo "no such file: $file" >&2; exit 1; }
umask 077
keys="POSTGRES_SUPERUSER_PASSWORD MEMO_MIGRATOR_PASSWORD MEMO_APP_PASSWORD S3_ADMIN_ACCESS_KEY S3_ADMIN_SECRET_KEY S3_APP_ACCESS_KEY S3_APP_SECRET_KEY ONLYOFFICE_JWT_SECRET MEMO_DEMO_PASSWORD"
tmp=$(mktemp)
cp "$file" "$tmp"
for key in $keys; do
  if grep -q "^$key=$" "$tmp" || ! grep -q "^$key=" "$tmp"; then
    value=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)
    if grep -q "^$key=" "$tmp"; then
      sed -i "s|^$key=$|$key=$value|" "$tmp"
    else
      echo "$key=$value" >>"$tmp"
    fi
    echo "generated $key"
  fi
done
mv "$tmp" "$file"
chmod 600 "$file"
