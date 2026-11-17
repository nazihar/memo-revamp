import { useQuery } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import { listOrgUnitsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { Named } from '@/api/generated/types.gen'
import { holds, PERMISSION, useMyAccess } from '@/features/access/access'
import { useTeamAccess } from '@/features/inbox/team'
import { dm, dmv } from './dashboard-messages'
import type { Weeks } from './dashboard-params'

export const DASHBOARD_READ = 'memo.dashboard.read'

/** The unit id of a `unit:<id>` scope. */
export function unitOf(scope: string): string | undefined {
  return scope.startsWith('unit:') ? scope.slice(5) : undefined
}

/** The first day of a period of [weeks] ending today, as the API's ISO date (the server ends it on its own today). */
export function periodStart(weeks: Weeks, today: Date = new Date()): string {
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - weeks * 7 + 1)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${String(start.getFullYear())}-${pad(start.getMonth() + 1)}-${pad(start.getDate())}`
}

/** An ISO date (`2026-11-15`) for people, in the viewer's language. */
export function formatDay(intl: IntlShape, day: string, options: Intl.DateTimeFormatOptions = { dateStyle: 'medium' }) {
  return intl.formatDate(`${day}T00:00:00Z`, { ...options, timeZone: 'UTC' })
}

export interface ScopeOption {
  value: string
  label: string
}

/**
 * The scopes the viewer may choose (design E05 §6): Me, the units they oversee (heads, from the team view), and the
 * units of their `memo.dashboard.read` grants. Grant units are named from the org tree when the viewer may read it,
 * or from the team view; a unit with no name in reach is shown as "Unit 1", "Unit 2"…, and the server checks the scope
 * either way.
 */
export function useScopeOptions(
  intl: IntlShape,
  current?: Named | null,
): { options: ScopeOption[]; teamUnits: Named[] } {
  const team = useTeamAccess()
  const access = useMyAccess()
  const grant = access.data?.permissions.find((p) => p.key === DASHBOARD_READ)
  const known = new Map<string, string>(team.units.map((u) => [u.id, u.name]))
  if (current) known.set(current.id, current.name)
  const needsTree = grant != null && (grant.tenantWide || grant.scopeUnitIds.some((id) => !known.has(id)))
  const tree = useQuery({
    ...listOrgUnitsOptions(),
    enabled: needsTree && holds(access.data, PERMISSION.directoryRead),
    staleTime: 5 * 60_000,
  })
  for (const unit of tree.data ?? []) if (!known.has(unit.id)) known.set(unit.id, unit.name)

  const ids = [
    ...team.units.map((u) => u.id),
    ...(grant?.scopeUnitIds ?? []),
    ...(grant?.tenantWide ? (tree.data ?? []).map((u) => u.id) : []),
  ]
  const unique = [...new Set(ids)]
  let unnamed = 0
  const units = unique
    .map((id) => ({ value: `unit:${id}`, label: known.get(id) ?? intl.formatMessage(dmv.unit, { number: ++unnamed }) }))
    .sort((a, b) => a.label.localeCompare(b.label, intl.locale))
  return { options: [{ value: 'me', label: intl.formatMessage(dm.scopeMe) }, ...units], teamUnits: team.units }
}
