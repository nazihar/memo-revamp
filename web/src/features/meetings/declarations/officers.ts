import type { IntlShape } from 'react-intl'
import type {
  AgendaDraftResponse,
  AgendaItemResponse,
  DeclarationResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  MemberResponse,
  QuorumWarningResponse,
  RulingResponse,
} from '@/api/generated/types.gen'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { om, omv } from './officer-messages'
import { refusal } from './words'

type State = DeclarationResponse['state']
type Status = MeetingResponse['status']

/** How a declaration or the chair's decision was given to an officer (flows T2, T3). */
export type How = 'AT_MEETING' | 'IN_PERSON' | 'EMAIL' | 'PHONE'
export const HOWS: How[] = ['AT_MEETING', 'IN_PERSON', 'EMAIL', 'PHONE']

/** Effective for the recusal rule (ADR-0062): the papers are withheld. */
export const effective = (state: State): boolean => state === 'RECUSED' || state === 'AWAITING_RULING'

/** Meeting statuses in which officers record, rule on and withdraw declarations (E04 §1.1 as built). */
export const officersAct = (status: Status): boolean =>
  status === 'DRAFT' || status === 'SCHEDULED' || status === 'IN_PROGRESS'

/** "5 Term loan, Delta Textiles", or "6 Restricted item" when the viewer may not read the title. */
export function itemLabel(
  intl: IntlShape,
  number: string | null | undefined,
  title: string | null | undefined,
): string {
  const shown = title?.length ? title : intl.formatMessage(om.restrictedItem)
  return number ? intl.formatMessage(omv.numbered, { number, title: shown }) : shown
}

/** "Leaving the room", "Waiting for a decision", "Taking part", "Withdrawn". */
export function stateWords(intl: IntlShape, state: State): string {
  switch (state) {
    case 'AWAITING_RULING':
      return intl.formatMessage(om.stateAwaiting)
    case 'TAKING_PART':
      return intl.formatMessage(om.stateTakingPart)
    case 'WITHDRAWN':
      return intl.formatMessage(om.stateWithdrawn)
    default:
      return intl.formatMessage(om.stateRecused)
  }
}

export const when = (intl: IntlShape, at: string, timeZone?: string): string =>
  intl.formatDate(at, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    ...(timeZone ? { timeZone } : {}),
  })

type Origin = Pick<DeclarationResponse, 'source' | 'how' | 'howNote' | 'recordedBy' | 'declaredAt'>

/** How a declaration came to be: "In the app, 9 Dec 2026", "By email, recorded by Company Secretary, 9 Dec 2026". */
export function originWords(intl: IntlShape, d: Origin, timeZone?: string): string {
  const date = when(intl, d.declaredAt, timeZone)
  switch (d.source) {
    case 'MEMO':
      return intl.formatMessage(om.originMemo)
    case 'EARLIER_MEETING':
      return intl.formatMessage(om.originMeeting)
    case 'ON_BEHALF': {
      const line = intl.formatMessage(omv.originRecorded, { how: d.how, name: d.recordedBy?.name ?? '', date })
      return d.howNote ? `${line} ${intl.formatMessage(omv.howNote, { note: d.howNote })}` : line
    }
    default:
      return intl.formatMessage(omv.originSelf, { date })
  }
}

/** "Chair Name decided: may take part, 10 Dec 2026." — with who recorded it on the chair's behalf, and how. */
export function rulingWords(intl: IntlShape, ruling: RulingResponse, timeZone?: string): string {
  const values = { name: ruling.by?.name ?? '', outcome: ruling.outcome, date: when(intl, ruling.at, timeZone) }
  return ruling.onBehalfBy
    ? intl.formatMessage(omv.ruledOnBehalf, { ...values, secretary: ruling.onBehalfBy.name, how: ruling.how })
    : intl.formatMessage(omv.ruled, values)
}

/**
 * The quorum warning in words (E04 §1.8): with the count of members short, "3 of 7 members have declared an interest
 * and the committee needs 4"; when a required role or composition is what cannot be met, that the rule cannot be met.
 */
export function quorumWords(intl: IntlShape, item: string, w: QuorumWarningResponse): string {
  const count = !w.recusedCount && (w.unmet.length === 0 || w.unmet.some((u) => u.kind === 'COUNT'))
  return count
    ? intl.formatMessage(omv.quorumCount, { item, declared: w.declared, basis: w.basis, needed: w.needed })
    : intl.formatMessage(omv.quorumRule, { item, declared: w.declared, basis: w.basis })
}

/** The committee's chair and vice-chair in office, by user id (from its members). */
export interface Chairs {
  chair?: string
  vice?: string
}

export function chairsOf(members: MemberResponse[] | undefined): Chairs {
  const holder = (role: MemberResponse['role']) =>
    members?.find((m) => m.role === role && m.state === 'IN_OFFICE' && m.person)?.person?.id
  const chair = holder('CHAIR')
  const vice = holder('VICE_CHAIR')
  return { ...(chair ? { chair } : {}), ...(vice ? { vice } : {}) }
}

/** Each item's parent, from the officers' draft or the members' agenda. */
export function parentsOf(agenda: { sections: { items: (AgendaItemResponse | MemberItemResponse)[] }[] } | undefined) {
  const parents = new Map<string, string>()
  for (const s of agenda?.sections ?? []) {
    for (const i of s.items) for (const sub of i.subItems) parents.set(sub.id, i.id)
  }
  return parents
}

/**
 * Who rules on [d] (E04 §1.2 as built): the first of the chair and the vice-chair in office who is not the declarant
 * and not recused from the item or its parent, as far as the viewer's register shows the recusals. The server decides;
 * this only chooses which button to offer.
 */
export function rulerOf(
  d: Pick<DeclarationResponse, 'itemId' | 'user'>,
  chairs: Chairs,
  recusedFrom: (itemId: string) => Set<string>,
): string | undefined {
  const recused = recusedFrom(d.itemId)
  return [chairs.chair, chairs.vice].find((id) => id !== undefined && id !== d.user.id && !recused.has(id))
}

/** The people recused from each item (an effective declaration on it or its parent), from the register's rows. */
export function recusals(
  items: { itemId: string; declarations: Pick<DeclarationResponse, 'state' | 'user'>[] }[],
  parents: Map<string, string>,
): (itemId: string) => Set<string> {
  const byItem = new Map<string, Set<string>>()
  for (const i of items) {
    byItem.set(i.itemId, new Set(i.declarations.filter((d) => effective(d.state)).map((d) => d.user.id)))
  }
  return (itemId) => {
    const parent = parents.get(itemId)
    return new Set([...(byItem.get(itemId) ?? []), ...(parent ? (byItem.get(parent) ?? []) : [])])
  }
}

/** Items an officer may record a declaration on: items and submissions of the draft they are not recused from. */
export function recordableItems(draft: AgendaDraftResponse | undefined): AgendaItemResponse[] {
  const out: AgendaItemResponse[] = []
  const visit = (i: AgendaItemResponse) => {
    if ((i.kind === 'ITEM' || i.kind === 'SUBMISSION') && !i.recused) out.push(i)
    i.subItems.forEach(visit)
  }
  for (const s of draft?.sections ?? []) s.items.forEach(visit)
  return out
}

/** The members' agenda items, flattened (for a vice-chair, who reads the members' agenda). */
export function memberItems(agenda: MemberAgendaResponse | undefined): MemberItemResponse[] {
  const out: MemberItemResponse[] = []
  const visit = (i: MemberItemResponse) => {
    out.push(i)
    i.subItems.forEach(visit)
  }
  for (const s of agenda?.sections ?? []) s.items.forEach(visit)
  return out
}

/** A refused officer action in words: the officers' reasons first, then the declaration refusals of flow S. */
export function officerRefusal(intl: IntlShape, problem: unknown): string {
  if (problemType(problem) === PROBLEM.rateLimited) return intl.formatMessage(om.rateLimited)
  if (isProblem(problem)) {
    switch (problem.reason) {
      case 'SELF_RULING':
        return intl.formatMessage(om.SELF_RULING)
      case 'NO_CHAIR':
        return intl.formatMessage(om.NO_CHAIR)
      case 'NOT_PUBLISHED':
        return intl.formatMessage(om.NOT_PUBLISHED)
      case 'ONE_OPEN':
        return intl.formatMessage(om.ONE_OPEN)
    }
    if (problem.status === 429) return intl.formatMessage(om.rateLimited)
    if (problem.status === 412) return intl.formatMessage(om.stale)
    if (problem.status === 403 && problem.reason !== 'RECUSED') return intl.formatMessage(om.forbidden)
  }
  return refusal(intl, problem)
}
