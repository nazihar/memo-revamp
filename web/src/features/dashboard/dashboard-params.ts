/**
 * The dashboard's address, apart from its page: the route validates it in the shell's bundle, so this module imports
 * nothing (no messages, no queries), or they would load on every first page.
 */
export type Weeks = 4 | 12 | 26
export const PERIODS: readonly Weeks[] = [4, 12, 26]

/** The dashboard's address: `scope=me|unit:<id>` and the period in weeks (12 when absent). */
export interface DashboardSearch {
  scope?: string
  weeks?: Weeks
}

export function parseDashboardSearch(search: Record<string, unknown>): DashboardSearch {
  const scope =
    typeof search.scope === 'string' && /^(me|unit:[\w-]{1,64})$/.test(search.scope) ? search.scope : undefined
  const weeks = PERIODS.find((w) => w === Number(search.weeks))
  return { ...(scope && scope !== 'me' ? { scope } : {}), ...(weeks && weeks !== 12 ? { weeks } : {}) }
}
