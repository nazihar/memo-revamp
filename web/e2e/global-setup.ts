import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { request, type FullConfig } from '@playwright/test'
import { apiSignIn, demoSecret, DEMO_USERS } from './auth'
import { authFile, type SignedInRole } from './roles'

/** The demo credentials exist where the local or CI stack generated them; the visual container has none. */
function credentialsAvailable(): boolean {
  try {
    demoSecret('MEMO_DEMO_PASSWORD')
    demoSecret('MEMO_DEMO_TOTP_SECRET')
    return true
  } catch {
    return false
  }
}

/**
 * Signs each role in once per run and stores its cookies (`e2e/.auth/<role>.json`, git-ignored). Skipped when no backend
 * answers or no demo credentials are available (visual and pseudo runs mock the API): role tests then fail with a clear
 * message instead.
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  if (!credentialsAvailable()) return
  const baseURL = config.projects[0]?.use.baseURL ?? 'http://localhost:4173'
  const probe = await request.newContext({ baseURL })
  const up = await probe
    .get('/api/v1/auth/session')
    .then((r) => r.ok())
    .catch(() => false)
  await probe.dispose()
  if (!up) return
  for (const role of Object.keys(DEMO_USERS) as SignedInRole[]) {
    const context = await request.newContext({ baseURL })
    await apiSignIn(context, baseURL, DEMO_USERS[role])
    const file = authFile(role)
    mkdirSync(dirname(file), { recursive: true })
    await context.storageState({ path: file })
    await context.dispose()
  }
}
