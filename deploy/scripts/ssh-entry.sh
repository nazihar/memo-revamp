#!/usr/bin/env bash
# Forced command for the CI deploy key (authorized_keys: command="/opt/memo/bin/ssh-entry.sh",no-pty,...).
# The key can do exactly one thing: `deploy <env> <backend@digest> <web@digest>`. Anything else is refused (threat
# model E05 S1). stdin carries one line `<registry-user> <registry-token>` (the CI job's short-lived, read-only token,
# so the VM stores no registry credential), then the release manifests (a .tgz of the repository's deploy/ tree).
set -euo pipefail
read -r -a cmd <<<"${SSH_ORIGINAL_COMMAND:-}"
if [[ ${#cmd[@]} -ne 4 || "${cmd[0]}" != deploy ]]; then
  echo "refused: only 'deploy <env> <backend@digest> <web@digest>' is allowed" >&2
  exit 1
fi
# bash reads a pipe one byte at a time here, so the .tgz that follows the line is left intact for head.
read -r registry_user registry_token || { echo "refused: missing registry credential line" >&2; exit 1; }
bundle=$(mktemp)
trap 'rm -f "$bundle"' EXIT
head -c 50000000 >"$bundle" # manifests only; a hard size cap
REGISTRY_USER=$registry_user REGISTRY_TOKEN=$registry_token \
  exec /opt/memo/bin/deploy.sh "${cmd[1]}" "${cmd[2]}" "${cmd[3]}" "$bundle"
