import type { QueryClient } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import type {
  AttendanceEventResponse,
  AttendancePersonResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  QuorumRule,
  QuorumSeatResponse,
  UnmetCondition,
} from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { am, amv } from './messages'

export type Person = AttendancePersonResponse
export type AttendanceEvent = AttendanceEventResponse
export type Mode = NonNullable<AttendancePersonResponse['mode']>
export type Kind = AttendanceEventResponse['kind']
export type Role = QuorumSeatResponse['role']

/** An item of the published agenda the register can name: "item 3", "3 Minutes of the last meeting". */
export interface AgendaItem {
  id: string
  number: string | null
  title: string | null
}

/** The published agenda's items in order, sub-items after their item, breaks left out ("Now: item n" offers these). */
export function agendaItems(agenda: MemberAgendaResponse | undefined): AgendaItem[] {
  const out: AgendaItem[] = []
  const add = (item: MemberItemResponse) => {
    if (item.kind !== 'BREAK') out.push({ id: item.id, number: item.number ?? null, title: item.title ?? null })
    item.subItems.forEach(add)
  }
  for (const section of agenda?.sections ?? []) section.items.forEach(add)
  return out
}

/** "item 3" (mid-sentence), or "an item" when the item is not on the agenda shown. */
export function itemWords(intl: IntlShape, items: readonly AgendaItem[], id: string | null | undefined): string {
  const item = items.find((i) => i.id === id)
  return item?.number ? intl.formatMessage(amv.item, { number: item.number }) : intl.formatMessage(am.anItem)
}

/** "Item 3" at the start of a sentence, or "This item". */
export function itemStart(intl: IntlShape, items: readonly AgendaItem[], id: string | null | undefined): string {
  const item = items.find((i) => i.id === id)
  return item?.number
    ? intl.formatMessage(amv.itemStart, { number: item.number })
    : intl.formatMessage(am.thisItemStart)
}

/** "3 Minutes of the last meeting", as the menu and the per-item view name an item. */
export function itemTitle(intl: IntlShape, item: AgendaItem): string {
  if (item.number && item.title) return intl.formatMessage(amv.numberTitle, { number: item.number, title: item.title })
  if (item.number) return intl.formatMessage(amv.item, { number: item.number })
  return item.title ?? intl.formatMessage(am.anItem)
}

/** "10:02" on the meeting's own clock. */
export function clock(intl: IntlShape, at: string, timeZone: string): string {
  return intl.formatTime(at, { hour: 'numeric', minute: '2-digit', timeZone })
}

/** "10:02" for a time input: [at] on [timeZone]'s clock, 24-hour. */
export function localTime(at: string | number, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone, hourCycle: 'h23', hour: '2-digit', minute: '2-digit' })
    .formatToParts(new Date(at))
    .reduce<Record<string, string>>((all, p) => ({ ...all, [p.type]: p.value }), {})
  return `${parts.hour ?? '00'}:${parts.minute ?? '00'}`
}

/** The ways a meeting held [mode] can be attended, in the order to offer them. */
export function modesFor(mode: MeetingResponse['mode']): Mode[] {
  switch (mode) {
    case 'IN_PERSON':
      return ['IN_PERSON']
    case 'ONLINE':
      return ['REMOTE']
    case 'HYBRID':
      return ['IN_PERSON', 'REMOTE']
  }
}

const ROLE_WORDS = {
  CHAIR: am.roleChair,
  VICE_CHAIR: am.roleViceChair,
  SECRETARY: am.roleSecretary,
  MEMBER: am.roleMember,
  STANDING_INVITEE: am.roleStandingInvitee,
  OBSERVER: am.roleObserver,
} as const

const ROLE_PHRASES = {
  CHAIR: am.phraseChair,
  VICE_CHAIR: am.phraseViceChair,
  SECRETARY: am.phraseSecretary,
  MEMBER: am.phraseMember,
  STANDING_INVITEE: am.phraseStandingInvitee,
  OBSERVER: am.phraseObserver,
} as const

export function roleWords(intl: IntlShape, role: Role): string {
  return intl.formatMessage(ROLE_WORDS[role])
}

/** "the chair or a vice-chair": any one of the roles of a requirement. */
export function rolePhrases(intl: IntlShape, roles: readonly Role[]): string {
  return intl.formatList(
    roles.map((r) => intl.formatMessage(ROLE_PHRASES[r])),
    { type: 'disjunction' },
  )
}

/** A condition of the rule that is not met, in words: "Needs the chair or a vice-chair present." */
export function unmetWords(intl: IntlShape, u: UnmetCondition, labels: Record<string, string>): string {
  switch (u.kind) {
    case 'ROLE':
      return intl.formatMessage(amv.unmetRole, { roles: rolePhrases(intl, u.roles ?? []) })
    case 'COMPOSITION': {
      const label = (labels[u.attribute ?? ''] ?? u.attribute ?? '').toLocaleLowerCase(intl.locale)
      return intl.formatMessage(u.majority ? amv.unmetMajority : amv.unmetComposition, {
        label,
        needed: u.needed ?? 0,
        actual: u.actual ?? 0,
      })
    }
    default:
      return intl.formatMessage(amv.unmetCount, { needed: u.needed ?? 0, actual: u.actual ?? 0 })
  }
}

/** The rule's defaults (ADR-0057) where the pinned rule is not at hand. */
const DEFAULT_RULE: QuorumRule = { counts: 'VOTING', recusedCount: false, remoteCounts: true, alternatesCount: true }

/**
 * Whether [seat] counts for the item's quorum under [rule] (the committee type's rule, when it is the version the meeting
 * is pinned to; the defaults otherwise): filled, in the room for the item, not recused unless recused members count,
 * online only where online attendance counts, an alternate only where alternates count.
 */
export function seatCounts(seat: QuorumSeatResponse, rule: QuorumRule | undefined): boolean {
  const r = rule ?? DEFAULT_RULE
  if (!seat.filledBy || seat.steppedOut) return false
  if (seat.recused && !(r.recusedCount ?? false)) return false
  if (seat.remote && !(r.remoteCounts ?? true)) return false
  return !seat.byAlternate || (r.alternatesCount ?? true)
}

/** Whether [seat] is in the count at all: voting seats only, where the rule counts voting members. */
export function seatInBasis(seat: QuorumSeatResponse, rule: QuorumRule | undefined): boolean {
  return (rule ?? DEFAULT_RULE).counts !== 'VOTING' || seat.voting
}

/** The person's last arrival, when they are present: the time "since" (they may have left and come back). */
export function lastArrival(person: Person, events: readonly AttendanceEvent[]): string | null {
  const arrivals = events.filter((e) => e.userId === person.userId && e.kind === 'ARRIVED' && !e.voided)
  return arrivals.reduce<string | null>((last, e) => (last == null || e.at > last ? e.at : last), null)
}

/** Whether the person's apologies came from their reply (written when the meeting started). */
export function apologiesFromReply(person: Person, events: readonly AttendanceEvent[]): boolean {
  const apologies = events.filter((e) => e.userId === person.userId && e.kind === 'APOLOGIES' && !e.voided)
  return apologies.at(-1)?.source === 'RSVP'
}

export interface Context {
  items: readonly AgendaItem[]
  timeZone: string
  events: readonly AttendanceEvent[]
}

/** Where the person stands, in words: "In person since 10:02", "Left at 11:42, during item 6", "Expected". */
export function stateWords(intl: IntlShape, p: Person, ctx: Context): string {
  switch (p.state) {
    case 'EXPECTED':
      return intl.formatMessage(am.expected)
    case 'APOLOGIES':
      return intl.formatMessage(apologiesFromReply(p, ctx.events) ? am.apologiesFromReply : am.apologies)
    case 'ABSENT':
      return intl.formatMessage(am.absent)
    case 'LEFT': {
      const at = p.lastLeftAt ? clock(intl, p.lastLeftAt, ctx.timeZone) : ''
      return p.leftDuringItem
        ? intl.formatMessage(amv.leftDuring, { at, item: itemWords(intl, ctx.items, p.leftDuringItem) })
        : intl.formatMessage(amv.leftAt, { at })
    }
    case 'PRESENT': {
      const since = lastArrival(p, ctx.events) ?? p.firstArrivedAt
      const at = since ? clock(intl, since, ctx.timeZone) : ''
      return intl.formatMessage(p.mode === 'REMOTE' ? amv.onlineSince : amv.inPersonSince, { at })
    }
  }
}

/** "joined during item 3 (late)" or "joined late", when they did. */
export function lateWords(intl: IntlShape, p: Person, ctx: Context): string | undefined {
  if (!p.joinedLate) return undefined
  return p.arrivedDuringItem
    ? intl.formatMessage(amv.joinedDuring, { item: itemWords(intl, ctx.items, p.arrivedDuringItem) })
    : intl.formatMessage(am.joinedLate)
}

const MODE_WORDS = { IN_PERSON: am.modeInPerson, REMOTE: am.modeOnline } as const

/** One entry of the register as the history and the correction dialog read it: "Arrived in person at 10:40, during item 3". */
export function eventWords(intl: IntlShape, e: AttendanceEvent, ctx: Omit<Context, 'events'>): string {
  const at = clock(intl, e.at, ctx.timeZone)
  const item = e.itemId ? itemWords(intl, ctx.items, e.itemId) : undefined
  const mode = e.mode ? intl.formatMessage(MODE_WORDS[e.mode]) : ''
  switch (e.kind) {
    case 'ARRIVED':
      return item
        ? intl.formatMessage(amv.eventArrivedDuring, { mode, at, item })
        : intl.formatMessage(amv.eventArrived, { mode, at })
    case 'MODE_CHANGED':
      return intl.formatMessage(amv.eventModeChanged, { mode, at })
    case 'LEFT':
      return item ? intl.formatMessage(amv.eventLeftDuring, { at, item }) : intl.formatMessage(amv.eventLeft, { at })
    case 'STEPPED_OUT':
      return intl.formatMessage(amv.eventSteppedOut, { item: item ?? intl.formatMessage(am.anItem) })
    case 'STEPPED_BACK':
      return intl.formatMessage(amv.eventSteppedBack, { item: item ?? intl.formatMessage(am.anItem) })
    case 'APOLOGIES':
      return intl.formatMessage(e.source === 'RSVP' ? am.eventApologiesReply : am.eventApologies)
    case 'ABSENT':
      return intl.formatMessage(am.eventAbsent)
    case 'VOIDED':
      return intl.formatMessage(am.eventVoided)
  }
}

/** A refused attendance write, in words; [name] is the person it was for. */
export function attendanceRefusal(intl: IntlShape, problem: unknown, name?: string): string {
  if (!isProblem(problem)) return intl.formatMessage(am.failed)
  const field = problem.status === 422 ? problem.errors?.[0]?.code : undefined
  switch (problem.reason ?? field) {
    case 'WRONG_STATE':
      return intl.formatMessage(am.WRONG_STATE)
    case 'FROZEN':
      return intl.formatMessage(am.FROZEN)
    case 'ALREADY_COUNTED':
      return name ? intl.formatMessage(amv.ALREADY_COUNTED, { name }) : intl.formatMessage(am.alreadyCounted)
    case 'TIME_OUT_OF_RANGE':
      return intl.formatMessage(am.TIME_OUT_OF_RANGE)
    case 'ITEM_NOT_ON_AGENDA':
      return intl.formatMessage(am.ITEM_NOT_ON_AGENDA)
    case 'MODE_REQUIRED':
      return intl.formatMessage(am.MODE_REQUIRED)
    case 'REASON_REQUIRED':
      return intl.formatMessage(am.REASON_REQUIRED)
    case 'NOT_INVITABLE':
      return intl.formatMessage(am.NOT_INVITABLE)
    case 'LENGTH':
      return intl.formatMessage(am.LENGTH)
  }
  if (problem.status === 412 || problem.status === 428) {
    return name ? intl.formatMessage(amv.stale, { name }) : intl.formatMessage(am.staleRegister)
  }
  if (problem.status === 409) return intl.formatMessage(am.WRONG_STATE)
  if (problem.status === 403 || problem.status === 404) return intl.formatMessage(am.forbidden)
  return intl.formatMessage(am.failed)
}

/** Whether [problem] means the register on screen is out of date (reload it). */
export function staleProblem(problem: unknown): boolean {
  return isProblem(problem) && (problem.status === 409 || problem.status === 412 || problem.status === 428)
}

const ATTENDANCE_QUERIES = new Set(['getMeetingAttendance', 'getMeetingQuorum', 'getMeetingQuorumChanges'])

/** Reloads everything that shows the register or the live quorum. */
export function refreshAttendance(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({
    predicate: (query) => ATTENDANCE_QUERIES.has((query.queryKey[0] as { _id?: string } | undefined)?._id ?? ''),
  })
}

/** The If-Match header for a change to a person's register row at [version]; none before their first entry. */
export function ifMatchRow(version: number | null | undefined): { 'If-Match'?: string } {
  return version == null ? {} : { 'If-Match': `"${String(version)}"` }
}

/** The live quorum is refreshed this often while the meeting is in progress (flow Y1). */
export const LIVE_REFRESH_MS = 15_000
