import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * Per-role authenticated sessions as Playwright storage states (TESTING_STRATEGY §4, Phase 0 Q5). The global setup
 * signs the demo organisation's users in through the real sign-in flow, MFA included, and writes
 * `e2e/.auth/<role>.json`. There is deliberately no test-only auth bypass in the application. Later phases add initiator,
 * approver, secretary and director.
 */
export type SignedInRole = 'tenantAdmin' | 'user'
export type Role = 'anonymous' | SignedInRole

export function authFile(role: SignedInRole): string {
  return fileURLToPath(new URL(`./.auth/${role}.json`, import.meta.url))
}

/** The storage state of [role]; undefined for anonymous. Fails clearly when the global setup could not sign in. */
export function storageStateFor(role: Role): string | undefined {
  if (role === 'anonymous') return undefined
  const file = authFile(role)
  if (!existsSync(file))
    throw new Error(`no signed-in state for ${role}: the global setup needs the backend (pnpm dev)`)
  return file
}
