import type { AgendaMoveRequest } from '@/api/generated/types.gen'
import { locate, type Draft } from './draft'

/** What is being dragged: an agenda item, or a submission from the tray. */
export type DragData = { type: 'item'; title: string } | { type: 'tray'; title: string }

/** Where a tray submission was dropped: a section and a position (null: the end). */
export interface PlaceTarget {
  sectionId: string
  position: number | null
}

export const SECTION = 'section:'
export const TRAY = 'tray:'

/** The move of [itemId] dropped over [overId] (an item or an empty section), or undefined when nothing moves. */
export function dropMove(
  draft: Pick<Draft, 'sections'>,
  itemId: string,
  overId: string,
): AgendaMoveRequest | undefined {
  const from = locate(draft, itemId)
  if (!from || overId === itemId) return undefined
  if (overId.startsWith(SECTION)) {
    if (from.parent) return undefined
    const section = draft.sections.find((s) => s.id === overId.slice(SECTION.length))
    return section ? { itemId, toSectionId: section.id, toParentId: null, toPosition: section.items.length } : undefined
  }
  const to = locate(draft, overId)
  if (!to) return undefined
  // Items move among items and sub-items among sub-items; a change of level is a menu command.
  if (Boolean(from.parent) !== Boolean(to.parent)) return undefined
  const parentId = to.parent?.id ?? null
  if (to.section.id === from.section.id && parentId === (from.parent?.id ?? null) && to.index === from.index)
    return undefined
  return { itemId, toSectionId: to.section.id, toParentId: parentId, toPosition: to.index }
}

/** Where a tray submission dropped over [overId] lands. */
export function dropPlace(draft: Pick<Draft, 'sections'>, overId: string): PlaceTarget | undefined {
  if (overId.startsWith(SECTION)) return { sectionId: overId.slice(SECTION.length), position: null }
  const to = locate(draft, overId)
  if (!to) return undefined
  return { sectionId: to.section.id, position: to.parent ? (to.parentIndex ?? 0) + 1 : to.index }
}
