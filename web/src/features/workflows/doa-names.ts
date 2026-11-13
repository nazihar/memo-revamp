import { useQuery } from '@tanstack/react-query'
import { listMemoTypesOptions, listOrgUnitsOptions, listRolesOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { DoaMember } from '@/api/generated/types.gen'

/** Names for what rules refer to by id: memo types, units, roles and positions. */
export function useDoaNames(positions: Record<string, string>) {
  const types = useQuery(listMemoTypesOptions())
  const units = useQuery(listOrgUnitsOptions())
  const roles = useQuery(listRolesOptions())
  return {
    types: types.data ?? [],
    units: units.data ?? [],
    roles: roles.data ?? [],
    member: (m: DoaMember): string => {
      if (m.kind === 'position') return (m.positionId && positions[m.positionId]) ?? m.positionId ?? ''
      return (roles.data ?? []).find((r) => r.id === m.roleId)?.name ?? m.roleId ?? ''
    },
  }
}

export type DoaNames = ReturnType<typeof useDoaNames>
