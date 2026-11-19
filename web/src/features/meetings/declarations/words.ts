import type { IntlShape } from 'react-intl'
import type {
  DeclarationResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  ReviewItemResponse,
} from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { pm, pmv } from '../page/messages'
import { dm, dmv } from './messages'

type State = DeclarationResponse['state']

/** A declaration that still counts (not withdrawn). */
export const isOpen = (d: Pick<DeclarationResponse, 'state'> | null | undefined): d is DeclarationResponse =>
  d != null && d.state !== 'WITHDRAWN'

/** Meeting statuses in which members declare, change their intent, withdraw and confirm the review (E04 §1.1). */
export const declaring = (status: MemberAgendaResponse['status']): boolean =>
  status === 'SCHEDULED' || status === 'IN_PROGRESS'

/** Items a member may declare on (E04 §1.3 as built): items and submissions whose title they see, never a break. */
export const declarable = (item: MemberItemResponse): boolean =>
  (item.kind === 'ITEM' || item.kind === 'SUBMISSION') && item.access !== 'HIDDEN' && Boolean(item.title)

/** "5 Term loan, Delta Textiles", or "6 Restricted item" when the title is not shown. */
export function itemName(intl: IntlShape, number: string | null | undefined, title: string | null | undefined): string {
  const shown = title?.length ? title : intl.formatMessage(pm.restrictedItem)
  return number ? intl.formatMessage(pmv.numbered, { number, title: shown }) : shown
}

/** A declaration's item as the declarant may name it. */
export const declarationItem = (intl: IntlShape, d: DeclarationResponse): string =>
  itemName(intl, d.itemNumber, d.itemTitle)

/** A review item's name. */
export const reviewItemName = (intl: IntlShape, i: ReviewItemResponse): string => itemName(intl, i.number, i.title)

/** "Declared — leaving the room" in the review list. */
export function declaredWords(intl: IntlShape, state: State): string {
  if (state === 'AWAITING_RULING') return intl.formatMessage(dm.declaredAsking)
  if (state === 'TAKING_PART') return intl.formatMessage(dm.declaredTakingPart)
  return intl.formatMessage(dm.declaredLeaving)
}

/** One line of the Declarations standing item: "S. Ali — will leave the room for item 5" (never the nature). */
export function standingLine(intl: IntlShape, state: State, item: string, name?: string): string {
  if (name === undefined) {
    if (state === 'AWAITING_RULING') return intl.formatMessage(dmv.selfAwaiting, { item })
    if (state === 'TAKING_PART') return intl.formatMessage(dmv.selfTakingPart, { item })
    return intl.formatMessage(dmv.selfRecused, { item })
  }
  if (state === 'AWAITING_RULING') return intl.formatMessage(dmv.otherAwaiting, { name, item })
  if (state === 'TAKING_PART') return intl.formatMessage(dmv.otherTakingPart, { name, item })
  return intl.formatMessage(dmv.otherRecused, { name, item })
}

export interface StandingLine {
  key: string
  text: string
}

/**
 * The declarations of the items the reader may see, for the Declarations standing item (E04 §1.5): the reader's own and
 * the others' open ones, in agenda order. The server lists the others' only on items the reader sees in full.
 */
export function standingLines(intl: IntlShape, agenda: MemberAgendaResponse): StandingLine[] {
  const lines: StandingLine[] = []
  const visit = (item: MemberItemResponse) => {
    const ref = item.number ?? item.title ?? ''
    if (isOpen(item.myDeclaration)) {
      lines.push({ key: item.myDeclaration.id, text: standingLine(intl, item.myDeclaration.state, ref) })
    }
    for (const d of item.declarations ?? []) {
      if (d.state !== 'WITHDRAWN') lines.push({ key: d.id, text: standingLine(intl, d.state, ref, d.user.name) })
    }
    item.subItems.forEach(visit)
  }
  agenda.sections.forEach((s) => {
    s.items.forEach(visit)
  })
  return lines
}

/** A refused declaration change, in words. */
export function refusal(intl: IntlShape, problem: unknown): string {
  if (!isProblem(problem)) return intl.formatMessage(dm.failed)
  switch (problem.reason) {
    case 'ONE_OPEN':
      return intl.formatMessage(dm.ONE_OPEN)
    case 'FROZEN':
      return intl.formatMessage(dm.FROZEN)
    case 'WRONG_STATE':
      return intl.formatMessage(dm.WRONG_STATE)
    case 'STALE_REVISION':
      return intl.formatMessage(dm.STALE_REVISION)
    case 'RECUSED':
      return intl.formatMessage(dm.RECUSED)
  }
  switch (problem.status) {
    case 403:
      return intl.formatMessage(dm.forbidden)
    case 404:
      return intl.formatMessage(dm.notFound)
    case 409:
      return intl.formatMessage(dm.WRONG_STATE)
    case 412:
      return intl.formatMessage(dm.stale)
  }
  return intl.formatMessage(dm.failed)
}

/** The code of the 422 problem on [field], if the server refused it. */
export function fieldCode(problem: unknown, field: string): string | undefined {
  if (!isProblem(problem) || problem.status !== 422) return undefined
  return problem.errors?.find((e) => e.field === field)?.code
}
