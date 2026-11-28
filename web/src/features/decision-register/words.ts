import type { IntlShape } from 'react-intl'
import type { DecisionRowView, RegisterScopeView } from '@/api/generated/types.gen'
import type { TermValues } from '@/features/terms/terms'
import { orgToday } from '@/lib/org-today'
import { drm } from './messages'
import { FLAGS, OUTCOMES, STATES, type Flag, type RegisterSearch } from './register-params'

/** A meeting's local date ("12 Dec 2026"): it is a calendar day, so it is shown as it is, in no time zone. */
export function dayWords(intl: IntlShape, day: string): string {
  return intl.formatDate(`${day}T00:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' })
}

/** An instant in the reader's time zone, as a day. */
export function instantDay(intl: IntlShape, instant: string): string {
  return intl.formatDate(instant, { dateStyle: 'medium' })
}

export function outcomeWords(intl: IntlShape, outcome: string): string {
  const known = OUTCOMES.find((o) => o === outcome)
  return known ? intl.formatMessage(drm[`outcome${known}`]) : outcome
}

export function stateWords(intl: IntlShape, state: string): string {
  const known = STATES.find((s) => s === state)
  return known ? intl.formatMessage(drm[`state${known}`]) : state
}

export function flagWords(intl: IntlShape, flag: Flag): string {
  return intl.formatMessage(drm[`flag${flag}`])
}

/** "BRC 3/2026, item 5", or the meeting's date when it has no label yet. */
export function meetingWords(intl: IntlShape, row: DecisionRowView): string {
  const meeting = row.meetingLabel ?? intl.formatMessage(drm.meetingOnly, { date: dayWords(intl, row.meetingDate) })
  return row.itemNumber ? intl.formatMessage(drm.meetingItem, { meeting, item: row.itemNumber }) : meeting
}

/** What a row says about its state: "Minutes approved 9 Jan 2027", "Sent to the memo", "Recorded at the meeting". */
export function rowStateWords(intl: IntlShape, row: DecisionRowView): string {
  if (row.state === 'FINAL' && row.finalAt) {
    return intl.formatMessage(drm.minutesApprovedOn, { date: instantDay(intl, row.finalAt) })
  }
  return stateWords(intl, row.state)
}

/** "In force" or "Void" for a numbered row. */
export function numberStateWords(intl: IntlShape, row: DecisionRowView): string | undefined {
  if (!row.resolutionNumber) return undefined
  return row.resolutionState === 'VOID' ? intl.formatMessage(drm.stateVOID) : intl.formatMessage(drm.inForce)
}

/** "2 conditions · with dissent · to be ratified · corrected · valid until 10 Mar 2027", for a row seen in full. */
export function exceptionWords(intl: IntlShape, row: DecisionRowView, today: string): string[] {
  const words: string[] = []
  const conditions = row.conditions?.length ?? 0
  if (conditions > 0) words.push(intl.formatMessage(drm.conditions, { count: conditions }))
  if (row.hasDissent) words.push(intl.formatMessage(drm.withDissent))
  if (row.ratificationRequired) {
    words.push(intl.formatMessage(row.ratifiedByDecisionId ? drm.ratified : drm.toBeRatified))
  }
  if (row.corrected) words.push(intl.formatMessage(drm.corrected))
  if (row.validUntil) {
    const date = dayWords(intl, row.validUntil)
    words.push(intl.formatMessage(row.validUntil < today ? drm.validityPassed : drm.validUntil, { date }))
  }
  return words
}

/** Today on the organisation's calendar (from the session), or on the reader's for a given [now], as an ISO date. */
export function todayIso(now?: Date): string {
  if (!now) return orgToday()
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 10)
}

/** A committee's name from the scope, with "(dissolved)" for one that no longer meets. */
export function committeeName(intl: IntlShape, scope: RegisterScopeView, forumId: string): string | undefined {
  const c = scope.committees.find((f) => f.id === forumId)
  if (!c) return undefined
  return c.dissolved ? intl.formatMessage(drm.dissolved, { name: c.name }) : c.name
}

export interface Chip {
  key: string
  name: string
  value: string
  without: RegisterSearch
}

/** The filters set in [search], each in words and with [search] without it, so a chip can remove it. */
export function chipsOf(intl: IntlShape, search: RegisterSearch, scope: RegisterScopeView, terms: TermValues): Chip[] {
  const chips: Chip[] = []
  const add = (key: keyof RegisterSearch, name: string, value: string, without: RegisterSearch) => {
    chips.push({ key, name, value, without })
  }
  if (search.committee) {
    add(
      'committee',
      intl.formatMessage(drm.committee, terms),
      committeeName(intl, scope, search.committee) ?? search.committee,
      { ...search, committee: undefined, sort: search.sort === 'number' ? undefined : search.sort },
    )
  }
  if (search.type) {
    add(
      'type',
      intl.formatMessage(drm.committeeType),
      scope.committeeTypes.find((t) => t.id === search.type)?.name ?? search.type,
      { ...search, type: undefined },
    )
  }
  if (search.from)
    add('from', intl.formatMessage(drm.from), dayWords(intl, search.from), { ...search, from: undefined })
  if (search.to) add('to', intl.formatMessage(drm.to), dayWords(intl, search.to), { ...search, to: undefined })
  if (search.outcome) {
    add('outcome', intl.formatMessage(drm.outcome), outcomeWords(intl, search.outcome), {
      ...search,
      outcome: undefined,
    })
  }
  if (search.resolutions) {
    chips.push({
      key: 'resolutions',
      name: intl.formatMessage(drm.resolutionsOnly),
      value: '',
      without: { ...search, resolutions: undefined },
    })
  }
  for (const flag of FLAGS.filter((f) => search.flag?.includes(f))) {
    const rest = search.flag?.filter((f) => f !== flag)
    chips.push({
      key: `flag:${flag}`,
      name: flagWords(intl, flag),
      value: '',
      without: { ...search, flag: rest?.length ? rest : undefined },
    })
  }
  if (search.memoType) {
    add(
      'memoType',
      intl.formatMessage(drm.memoType),
      scope.memoTypes.find((t) => t.id === search.memoType)?.name ?? search.memoType,
      { ...search, memoType: undefined },
    )
  }
  if (search.state) {
    add('state', intl.formatMessage(drm.state), stateWords(intl, search.state), { ...search, state: undefined })
  }
  return chips.map((c) => ({ ...c, without: clean(c.without) }))
}

/** A chip's words: "Committee: Board Risk Committee", or the filter's name alone ("With dissent"). */
export function chipWords(intl: IntlShape, chip: Chip): string {
  return chip.value ? intl.formatMessage(drm.chip, { name: chip.name, value: chip.value }) : chip.name
}

function clean(search: RegisterSearch): RegisterSearch {
  return Object.fromEntries(Object.entries(search).filter(([, v]) => v !== undefined))
}

/** The id of a row's element that takes focus: its title link (in full) or the row itself (a restricted line). */
export function rowFocusId(row: DecisionRowView): string {
  return `decision-${row.decisionId}`
}
