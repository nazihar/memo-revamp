/**
 * Per-role authenticated sessions as Playwright storage states (TESTING_STRATEGY §4, Phase 0 Q5).
 *
 * Phase 0 has no authentication, so only `anonymous` exists. Phase 1a adds initiator, approver, secretary, director
 * and tenant-admin: a global setup logs each demo-tenant user in through the real login flow (with MFA) and writes
 * `e2e/.auth/<role>.json`. There is deliberately no test-only auth bypass in the application.
 */
export type Role = 'anonymous'

type StorageState = string | undefined

export const roles: Record<Role, { storageState: StorageState }> = {
  anonymous: { storageState: undefined },
}
