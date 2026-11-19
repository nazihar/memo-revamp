import type {
  AgendaItemChangeRequest,
  AgendaPaperChangeRequest,
  SectionRequest,
  AgendaDraftResponse,
  AgendaItemResponse,
  AgendaMoveRequest,
  AgendaPaperResponse,
  AgendaSectionResponse,
} from '@/api/generated/types.gen'

export type Draft = AgendaDraftResponse
export type Section = AgendaSectionResponse
export type Item = AgendaItemResponse
export type Paper = AgendaPaperResponse
export type Purpose = NonNullable<Item['purpose']>

export const PURPOSES: Purpose[] = ['DECISION', 'DISCUSSION', 'INFORMATION']

/** The If-Match header of a change to the draft: its version, as the server's ETag spells it. */
export function draftIfMatch(draft: Pick<Draft, 'version'>): { 'If-Match': string } {
  return { 'If-Match': `"${String(draft.version)}"` }
}

/** Where an item sits: its section, its parent (for a sub-item), its index among its siblings and those siblings. */
export interface Place {
  section: Section
  sectionIndex: number
  parent?: Item
  /** The parent's index in its section, for a sub-item. */
  parentIndex?: number
  index: number
  siblings: Item[]
}

/** Finds [itemId] in the draft, at either level. */
export function locate(draft: Pick<Draft, 'sections'>, itemId: string): Place | undefined {
  for (const [sectionIndex, section] of draft.sections.entries()) {
    for (const [index, item] of section.items.entries()) {
      if (item.id === itemId) return { section, sectionIndex, index, siblings: section.items }
      const sub = item.subItems.findIndex((s) => s.id === itemId)
      if (sub >= 0) {
        return { section, sectionIndex, parent: item, parentIndex: index, index: sub, siblings: item.subItems }
      }
    }
  }
  return undefined
}

/** Every item of the draft, sub-items after their item. */
export function allItems(draft: Pick<Draft, 'sections'>): Item[] {
  return draft.sections.flatMap((s) => s.items.flatMap((i) => [i, ...i.subItems]))
}

/** The Move menu's one-step commands (flow O2). */
export type MoveCommand = 'up' | 'down' | 'indent' | 'outdent'

/**
 * The move body of a one-step command, or undefined when it cannot apply (design E03 §1.2, `ONE_LEVEL`). Positions are
 * the place among the siblings once the item is taken out, from 0, as `POST …/agenda/moves` reads them. An item at the
 * top or bottom of its section moves into the section before or after it, so the commands alone reach every place.
 */
export function moveBody(
  draft: Pick<Draft, 'sections'>,
  itemId: string,
  command: MoveCommand,
): AgendaMoveRequest | undefined {
  const at = locate(draft, itemId)
  if (!at) return undefined
  const item = at.siblings[at.index]
  if (!item) return undefined
  const parentId = at.parent?.id ?? null
  switch (command) {
    case 'up': {
      if (at.index > 0) return { itemId, toSectionId: at.section.id, toParentId: parentId, toPosition: at.index - 1 }
      const before = draft.sections[at.sectionIndex - 1]
      if (!at.parent && before) {
        return { itemId, toSectionId: before.id, toParentId: null, toPosition: before.items.length }
      }
      return undefined
    }
    case 'down': {
      if (at.index < at.siblings.length - 1) {
        return { itemId, toSectionId: at.section.id, toParentId: parentId, toPosition: at.index + 1 }
      }
      const after = draft.sections[at.sectionIndex + 1]
      if (!at.parent && after) return { itemId, toSectionId: after.id, toParentId: null, toPosition: 0 }
      return undefined
    }
    case 'indent': {
      const above = at.siblings[at.index - 1]
      if (at.parent || !above || item.kind === 'BREAK' || above.kind === 'BREAK' || item.subItems.length > 0) {
        return undefined
      }
      return { itemId, toSectionId: at.section.id, toParentId: above.id, toPosition: above.subItems.length }
    }
    case 'outdent': {
      if (!at.parent || at.parentIndex == null) return undefined
      return { itemId, toSectionId: at.section.id, toParentId: null, toPosition: at.parentIndex + 1 }
    }
  }
}

/** The move of a whole section one place up or down, or undefined at the edge. */
export function sectionMove(
  draft: Pick<Draft, 'sections'>,
  sectionId: string,
  up: boolean,
): AgendaMoveRequest | undefined {
  const index = draft.sections.findIndex((s) => s.id === sectionId)
  const to = up ? index - 1 : index + 1
  if (index < 0 || to < 0 || to >= draft.sections.length) return undefined
  return { sectionId, toPosition: to }
}

/** Where an item is after a change, for the announcement: its position (from 1) in its list and the section's title. */
export function whereNow(
  draft: Pick<Draft, 'sections'>,
  itemId: string,
): { item: Item; position: number; section: string; parent?: Item } | undefined {
  const at = locate(draft, itemId)
  const item = at?.siblings[at.index]
  if (!at || !item) return undefined
  return { item, position: at.index + 1, section: at.section.title, ...(at.parent ? { parent: at.parent } : {}) }
}

/** Papers and items waiting for the chair: what Q2 lists. */
export interface Waiting {
  item: Item
  paper?: Paper
}

export function waitingForChair(draft: Pick<Draft, 'sections'>): Waiting[] {
  return allItems(draft).flatMap((item): Waiting[] => [
    ...(item.late === 'AWAITING_CHAIR' ? [{ item }] : []),
    ...item.papers.filter((p) => p.late === 'AWAITING_CHAIR').map((paper) => ({ item, paper })),
  ])
}

/** "1 h 30 min", "45 min", "2 h" from minutes, with the locale's units. */
export function duration(locale: string, minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  const unit = (value: number, u: 'hour' | 'minute') =>
    new Intl.NumberFormat(locale, { style: 'unit', unit: u, unitDisplay: 'short' }).format(value)
  if (h && m) return `${unit(h, 'hour')} ${unit(m, 'minute')}`
  return h ? unit(h, 'hour') : unit(m, 'minute')
}

/** A local date-time from the server (`2026-12-12T10:25:00`) as the time of day in the locale ("10:25 AM"). */
export function localTime(locale: string, local: string): string {
  const [, time = '00:00'] = local.split('T')
  const [hours = 0, minutes = 0] = time.split(':').map(Number)
  return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }).format(
    new Date(Date.UTC(2000, 0, 1, hours, minutes)),
  )
}

/** The time of day of [instant] on the meeting's clock. */
export function timeIn(locale: string, instant: string, zone: string): string {
  return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit', timeZone: zone }).format(
    new Date(instant),
  )
}

/**
 * The whole item as `PATCH …/agenda/items/{id}` takes it (the server replaces every field), with [overrides] applied:
 * a save of one tab never clears what another tab set.
 */
export function itemChange(item: Item, overrides: Partial<AgendaItemChangeRequest> = {}): AgendaItemChangeRequest {
  return {
    title: item.title,
    description: item.description ?? null,
    purpose: item.purpose,
    resolutionSought: item.resolutionSought ?? null,
    minutes: item.minutes ?? null,
    presenters: item.presenters.map((p) => (p.userId ? { userId: p.userId } : { name: p.name })),
    attendees: item.attendees.map((a) => a.id),
    restrictedTo: item.restrictedTo ? item.restrictedTo.map((p) => p.id) : null,
    titleHidden: item.titleHidden,
    classificationId: item.classificationId,
    ...overrides,
  }
}

/** The whole paper as `PATCH …/agenda/papers/{id}` takes it, with [overrides] applied. */
export function paperChange(paper: Paper, overrides: Partial<AgendaPaperChangeRequest> = {}): AgendaPaperChangeRequest {
  return {
    title: paper.title,
    restrictedTo: paper.restrictedTo ? paper.restrictedTo.map((p) => p.id) : null,
    classificationId: paper.classificationId,
    ...overrides,
  }
}

/** The whole section as `PATCH …/agenda/sections/{id}` takes it, with [overrides] applied. */
export function sectionChange(section: Section, overrides: Partial<SectionRequest> = {}): SectionRequest {
  return {
    title: section.title,
    kind: section.kind,
    membersOnly: section.membersOnly,
    receivesSubmissions: section.receivesSubmissions,
    ...overrides,
  }
}
