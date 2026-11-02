#!/bin/sh
# Fills every empty secret in an on-prem .env file with a fresh random value; never overwrites an existing value, except
# a malformed demo TOTP secret (demo-only, replaced so the backend can start).
#   deploy/scripts/gen-secrets.sh /opt/memo/dev/.env
set -eu
file=${1:?usage: gen-secrets.sh <env-file>}
[ -f "$file" ] || { echo "no such file: $file" >&2; exit 1; }
umask 077
keys="POSTGRES_SUPERUSER_PASSWORD MEMO_MIGRATOR_PASSWORD MEMO_APP_PASSWORD S3_ADMIN_ACCESS_KEY S3_ADMIN_SECRET_KEY S3_APP_ACCESS_KEY S3_APP_SECRET_KEY ONLYOFFICE_JWT_SECRET MEMO_DEMO_PASSWORD MEMO_DEMO_TOTP_SECRET MEMO_DATA_KEY"

# 32 random characters from the given set. Reads far more random bytes than needed (tr keeps only a fraction of them)
# and refuses to return anything shorter.
random_chars() {
  value=$(head -c 4096 /dev/urandom | LC_ALL=C tr -dc "$1" | head -c 32)
  [ "${#value}" -eq 32 ] || { echo "could not generate a random value" >&2; exit 1; }
  printf '%s' "$value"
}

tmp=$(mktemp)
cp "$file" "$tmp"
# Base32 secrets are 32 characters A-Z, 2-7 (160 bits); an earlier version of this script could write fewer.
if grep -q '^MEMO_DEMO_TOTP_SECRET=.' "$tmp" && ! grep -Eq '^MEMO_DEMO_TOTP_SECRET=[A-Z2-7]{32}$' "$tmp"; then
  sed -i 's|^MEMO_DEMO_TOTP_SECRET=.*$|MEMO_DEMO_TOTP_SECRET=|' "$tmp"
  echo "replacing malformed MEMO_DEMO_TOTP_SECRET"
fi
for key in $keys; do
  if grep -q "^$key=$" "$tmp" || ! grep -q "^$key=" "$tmp"; then
    if [ "$key" = MEMO_DEMO_TOTP_SECRET ]; then
      # Base32 (authenticator apps' secret format): 32 characters = 160 bits.
      value=$(random_chars 'A-Z2-7')
    else
      value=$(random_chars 'A-Za-z0-9')
    fi
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
