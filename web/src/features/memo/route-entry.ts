import type { RouteEntryView } from '@/api/generated/types.gen'

/**
 * A short label for a route entry (design E04 §4.7): the person, the position with who holds it today, or a group's
 * members. The route editor for positions and groups comes with E04 T8.
 */
export function routeEntryLabel(entry: RouteEntryView): string {
  if (entry.approver) return entry.approver.name
  if (entry.position) {
    const holder = entry.position.holder?.name
    return holder ? `${entry.position.title} (${holder})` : entry.position.title
  }
  return (entry.group?.members ?? []).map(routeEntryLabel).join(' / ')
}
