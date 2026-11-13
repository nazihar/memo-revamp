import type { IntlShape } from 'react-intl'
import type { RouteEntry, RouteEntryView } from '@/api/generated/types.gen'
import { rmv } from './route-messages'

/** A person or a position on a route, or a member of a group. */
export interface RouteMember {
  kind: 'person' | 'position'
  id: string
  name: string
}

/** One entry of the route as the editor holds it (design E04 §4.7). */
export type RouteItem = RouteMember | { kind: 'group'; mode: 'ALL' | 'ANY'; members: RouteMember[] }

function member(intl: IntlShape, view: RouteEntryView): RouteMember | undefined {
  if (view.approver) return { kind: 'person', id: view.approver.id, name: view.approver.name }
  if (view.position) {
    const { title, holder } = view.position
    const name = holder
      ? intl.formatMessage(rmv.positionLabel, { title, holder: holder.name })
      : intl.formatMessage(rmv.positionVacant, { title })
    return { kind: 'position', id: view.position.id, name }
  }
  return undefined
}

/** The editor's entry for a route entry of the memo view. */
export function fromView(intl: IntlShape, view: RouteEntryView): RouteItem | undefined {
  if (view.group) {
    const members = view.group.members.flatMap((m) => {
      const found = member(intl, m)
      return found ? [found] : []
    })
    return { kind: 'group', mode: view.group.mode, members }
  }
  return member(intl, view)
}

/** The entry as the route API takes it. */
export function toRequest(item: RouteItem): RouteEntry {
  if (item.kind === 'group') return { group: { mode: item.mode, members: item.members.map(toRequest) } }
  return item.kind === 'person' ? { userId: item.id } : { positionId: item.id }
}

/** A stable key for a list of entries. */
export function itemKey(item: RouteItem): string {
  return item.kind === 'group' ? `group:${item.members.map(itemKey).join('+')}` : `${item.kind}:${item.id}`
}

/** A short label: the person, the position with who holds it now, or the group and its members. */
export function itemLabel(intl: IntlShape, item: RouteItem): string {
  if (item.kind !== 'group') return item.name
  return intl.formatMessage(rmv.groupLabel, { mode: item.mode, names: item.members.map((m) => m.name).join(', ') })
}

/** The label of a route entry of the memo view. */
export function routeEntryLabel(intl: IntlShape, entry: RouteEntryView): string {
  const item = fromView(intl, entry)
  return item ? itemLabel(intl, item) : ''
}
