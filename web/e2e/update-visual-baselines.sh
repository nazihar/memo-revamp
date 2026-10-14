#!/bin/sh
# Renders visual-regression baselines on Linux, inside the official Playwright image, so they match nightly CI.
# Runs only @playwright/test + @axe-core/playwright in the container (pure JS); the app is served by `vite preview`
# from the host at E2E_BASE_URL (visual specs mock the API, so no backend is needed).
#
#   pnpm --filter web build:e2e && pnpm --filter web preview --host 0.0.0.0   # terminal 1
#   pnpm --filter web e2e:visual:update                                        # terminal 2
set -eu
: "${E2E_BASE_URL:=http://host.docker.internal:4173}"
work=$(mktemp -d)
cd "$work"
npm init -y >/dev/null
npm install --no-audit --no-fund --silent @playwright/test@1.64.0 @axe-core/playwright@4.13.0
cp -r /web/e2e /web/playwright.config.ts /web/security-headers.json .
E2E_BASE_URL="$E2E_BASE_URL" npx playwright test --project=visual --update-snapshots "$@"
rm -rf /web/e2e/__screenshots__
cp -r e2e/__screenshots__ /web/e2e/
echo "Baselines written to web/e2e/__screenshots__ — review the images before committing."
