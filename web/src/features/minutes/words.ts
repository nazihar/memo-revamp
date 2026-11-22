import type { IntlShape, MessageDescriptor } from 'react-intl'
import type {
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  MinutesApprovalResponse,
  MinutesCopySetResponse,
  MinutesRedactedItem,
  MinutesRedactionResponse,
  MinutesResponse,
} from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { fullName } from '@/features/meetings/shared'
import { mn, mnv } from './messages'

export type Minutes = MinutesResponse
export type Stage = MinutesResponse['state']

/** The stages in which readers have a copy to read (the server's `mayReadCopy`, as a set). */
export const READ_STAGES: Stage[] = ['CIRCULATED', 'AWAITING_CHAIR', 'APPROVED']

/** The meeting's name for the minutes: "Board Risk Committee — BRC 3/2026", or "the meeting" when it is not known. */
export function meetingWords(intl: IntlShape, meeting: MeetingResponse | undefined): string {
  return meeting ? fullName(intl, meeting) : intl.formatMessage(mn.title)
}

/** "12 December 2026" on the meeting's own calendar. */
export function day(intl: IntlShape, at: string, timeZone?: string): string {
  return intl.formatDate(at, { day: 'numeric', month: 'long', year: 'numeric', ...(timeZone ? { timeZone } : {}) })
}

/** "Thursday 19 December" (a deadline). */
export function weekday(intl: IntlShape, at: string, timeZone?: string): string {
  return intl.formatDate(at, { weekday: 'long', day: 'numeric', month: 'long', ...(timeZone ? { timeZone } : {}) })
}

/** "12 Dec 2026, 15:20". */
export function moment(intl: IntlShape, at: string, timeZone?: string): string {
  return intl.formatDate(at, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    ...(timeZone ? { timeZone } : {}),
  })
}

/** An item of the minutes with its title when the agenda shows it. */
export interface MinutesItem {
  itemId: string
  number: string
  title?: string | null
}

/** The agenda's item titles by id (items and sub-items), where the reader sees them. */
export function titlesOf(agenda: MemberAgendaResponse | undefined): Map<string, string> {
  const titles = new Map<string, string>()
  const add = (item: MemberItemResponse) => {
    if (item.title && item.access !== 'HIDDEN') titles.set(item.id, item.title)
    item.subItems.forEach(add)
  }
  agenda?.sections.forEach((s) => {
    s.items.forEach(add)
  })
  return titles
}

/** "Item 5 — Term loan, Delta Textiles", or "Item 5". */
export function itemName(intl: IntlShape, item: MinutesItem): string {
  return item.title
    ? intl.formatMessage(mnv.itemTitle, { number: item.number, title: item.title })
    : intl.formatMessage(mnv.item, { number: item.number })
}

/** "5, 6 and 8". */
export function numbers(intl: IntlShape, items: readonly { number?: string | null }[]): string {
  return intl.formatList(
    items.map((i) => i.number ?? '?'),
    { type: 'conjunction' },
  )
}

/** "item 5 (declared an interest), item 6 (restricted)". */
export function redactedWords(intl: IntlShape, items: readonly (MinutesRedactionResponse | MinutesRedactedItem)[]) {
  return intl.formatList(
    items.map((r) =>
      intl.formatMessage(mnv.redactedItem, {
        number: ('itemNumber' in r ? r.itemNumber : r.number) ?? '?',
        why: intl.formatMessage(mn[r.kind]),
      }),
    ),
    { type: 'conjunction' },
  )
}

/** "password and authenticator". */
export function factorWords(intl: IntlShape, approval: Pick<MinutesApprovalResponse, 'factors'>): string {
  return intl.formatMessage(approval.factors.includes('TOTP') ? mn.withAuthenticator : mn.withPassword)
}

/** The `reason` of a refusal (409, 403), if any. */
export function reasonOf(problem: unknown): string | undefined {
  return isProblem(problem) && typeof problem.reason === 'string' ? problem.reason : undefined
}

/** The items a 409 `STRUCTURE` or `UNDECIDED_ITEMS` names. */
function itemsOf(problem: unknown): { number?: string | null }[] {
  const items = (problem as { items?: unknown } | null)?.items
  return Array.isArray(items) ? (items as { number?: string | null }[]) : []
}

/** Whether a refusal means the page is out of date (reload it). */
export function staleProblem(problem: unknown): boolean {
  return isProblem(problem) && (problem.status === 409 || problem.status === 412 || problem.status === 428)
}

const REASONS: Record<string, MessageDescriptor> = {
  NO_DRAFT: mn.NO_DRAFT,
  NOT_AGREED: mn.NOT_AGREED,
  UNRESOLVED_CHANGES: mn.UNRESOLVED_CHANGES,
  WRONG_STATE: mn.WRONG_STATE,
  FROZEN: mn.FROZEN,
  GENERATING: mn.GENERATING,
  DRAFT_EXISTS: mn.DRAFT_EXISTS,
  BEING_EDITED: mn.BEING_EDITED,
  RECUSED: mn.RECUSED_STEP,
  NOT_CONFIRMER: mn.NOT_CONFIRMER,
  NOT_REVIEWER: mn.NOT_REVIEWER,
  SECRETARY: mn.SECRETARY,
  ALREADY_ANSWERED: mn.ALREADY_ANSWERED,
  TOO_MANY_COPIES: mn.TOO_MANY_COPIES,
  DECIDED: mn.DECIDED,
}

/** A refusal of a minutes request in plain words (design E06 §3's problems). */
export function refusal(intl: IntlShape, problem: unknown): string {
  const reason = reasonOf(problem)
  if (reason === 'STRUCTURE') {
    const items = itemsOf(problem)
    return items.length
      ? intl.formatMessage(mnv.structureItem, { number: numbers(intl, items) })
      : intl.formatMessage(mn.structureTitle)
  }
  if (reason === 'UNDECIDED_ITEMS') {
    const items = itemsOf(problem)
    return intl.formatMessage(mnv.undecided, { count: items.length, items: numbers(intl, items) })
  }
  const known = reason ? REASONS[reason] : undefined
  if (known) return intl.formatMessage(known)
  if (isProblem(problem) && problem.status === 412) return intl.formatMessage(mn.stale)
  if (isProblem(problem) && problem.status === 403) return intl.formatMessage(mn.forbidden)
  if (isProblem(problem) && problem.status === 422 && problem.errors?.length) {
    return problem.errors.map((e) => fieldWords(intl, e.code)).join(' ')
  }
  return intl.formatMessage(mn.failed)
}

/** A field problem's code in words. */
export function fieldWords(intl: IntlShape, code: string | undefined): string {
  switch (code) {
    case 'LENGTH':
      return intl.formatMessage(mn.tooLong)
    case 'REASON_REQUIRED':
      return intl.formatMessage(mn.reasonRequired)
    case 'TEXT_REQUIRED':
      return intl.formatMessage(mn.writeSuggestion)
    case 'RANGE':
      return intl.formatMessage(mn.dateRange)
    case 'NOT_CLEARED':
      return intl.formatMessage(mn.askNotCleared)
    default:
      return intl.formatMessage(mn.failed)
  }
}

/** Field problems of a 422 by field, in words (empty for any other problem). */
export function fieldProblems(intl: IntlShape, problem: unknown): Record<string, string> {
  if (!isProblem(problem) || problem.status !== 422 || !problem.errors?.length) return {}
  return Object.fromEntries(problem.errors.map((e) => [e.field, fieldWords(intl, e.code)]))
}

/** The work's failure code in words. */
export function workFailure(intl: IntlShape, code: string | null | undefined): string {
  if (code === 'INVALID_TEMPLATE') return intl.formatMessage(mn.INVALID_TEMPLATE)
  if (code === 'MISSING_VALUE') return intl.formatMessage(mn.MISSING_VALUE)
  if (code === 'CONTROLS') return intl.formatMessage(mn.CONTROLS)
  return intl.formatMessage(mn.workFailed)
}

/** "a3f9c1…c21e": the start and end of a SHA-256, as people compare it. */
export function fingerprint(sha256: string): string {
  return sha256.length > 12 ? `${sha256.slice(0, 6)}…${sha256.slice(-4)}` : sha256
}

/** If-Match on the minutes' version. */
export function ifMatch(page: Pick<Minutes, 'version'>): { 'If-Match': string } {
  return { 'If-Match': `"${String(page.version)}"` }
}

/** "Demo Director — item 5 (declared an interest) left out", or "Everyone else — the full minutes". */
export function copyLabel(intl: IntlShape, c: MinutesCopySetResponse): string {
  if (!c.redacted.length) return intl.formatMessage(mn.everyoneElse)
  return intl.formatMessage(mnv.copyFor, {
    names: intl.formatList(
      c.readers.map((r) => r.name),
      { type: 'conjunction' },
    ),
    items: redactedWords(intl, c.redacted),
  })
}
