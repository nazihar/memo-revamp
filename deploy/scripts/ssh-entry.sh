#!/usr/bin/env bash
# Forced command for the CI deploy key (authorized_keys: command="/opt/memo/bin/ssh-entry.sh",no-pty,...).
# The key can do exactly one thing: `deploy <env> <backend@digest> <web@digest>` with the release manifests on stdin
# (a .tgz of the repository's deploy/ tree). Anything else is refused (threat model E05 S1).
set -euo pipefail
read -r -a cmd <<<"${SSH_ORIGINAL_COMMAND:-}"
if [[ ${#cmd[@]} -ne 4 || "${cmd[0]}" != deploy ]]; then
  echo "refused: only 'deploy <env> <backend@digest> <web@digest>' is allowed" >&2
  exit 1
fi
bundle=$(mktemp)
trap 'rm -f "$bundle"' EXIT
head -c 50000000 >"$bundle" # manifests only; a hard size cap
exec /opt/memo/bin/deploy.sh "${cmd[1]}" "${cmd[2]}" "${cmd[3]}" "$bundle"
