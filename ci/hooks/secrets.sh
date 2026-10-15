#!/bin/sh
# Pre-commit secret scan of staged changes (ADR-0013). gitleaks is optional locally; CI always enforces it.
if command -v gitleaks >/dev/null 2>&1; then
  exec gitleaks git --staged --config .gitleaks.toml --redact --no-banner
fi
echo "gitleaks not installed: secret scan skipped locally (CI enforces it). Install: https://github.com/gitleaks/gitleaks"
