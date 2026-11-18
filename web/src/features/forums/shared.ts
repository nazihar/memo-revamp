import type { QueryClient } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import type { ForumPolicyResponse, ForumResponse, MemberResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import type { TermValues } from '@/features/terms/terms'
import { fm, fmt } from './messages'

export type Role = MemberResponse['role']
export type EndReason = NonNullable<MemberResponse['endReason']>

export const ROLES: Role[] = ['CHAIR', 'VICE_CHAIR', 'SECRETARY', 'MEMBER', 'STANDING_INVITEE', 'OBSERVER']

/** Roles a secretary may not give or change (`SECRETARY_LIMIT`, design E01 §1). */
export const ADMIN_ROLES: Role[] = ['CHAIR', 'SECRETARY']

/** Whether a role votes unless the charter says otherwise (`ForumRole.votesByDefault`). */
export function votesByDefault(role: Role): boolean {
  return role === 'CHAIR' || role === 'VICE_CHAIR' || role === 'MEMBER'
}

/** The reasons a person may give for ending a membership; renewals, role changes and withdrawals have their own action. */
export const END_REASONS: EndReason[] = ['RESIGNED', 'TERM_COMPLETED', 'REMOVED', 'OTHER']

/** Today in the browser as `YYYY-MM-DD` (the server checks dates in the organisation's time zone). */
export function today(): string {
  return isoDay(new Date())
}

function isoDay(d: Date): string {
  return `${String(d.getFullYear())}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function parse(day: string): Date {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1)
}

/** [day] plus [n] days. */
export function addDays(day: string, n: number): string {
  const d = parse(day)
  d.setDate(d.getDate() + n)
  return isoDay(d)
}

/** The last day of a term of [months] starting on [day], as the server computes it (`plusMonths(n).minusDays(1)`). */
export function termEnd(day: string, months: number): string {
  const start = parse(day)
  const end = new Date(start.getFullYear(), start.getMonth() + months, 1)
  const lastOfMonth = new Date(end.getFullYear(), end.getMonth() + 1, 0).getDate()
  end.setDate(Math.min(start.getDate(), lastOfMonth))
  end.setDate(end.getDate() - 1)
  return isoDay(end)
}

/** A calendar day (`YYYY-MM-DD`) as the reader's language writes it, without shifting it across time zones. */
export function formatDay(intl: IntlShape, day: string): string {
  return intl.formatDate(`${day}T00:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' })
}

/** The person a row is about: the member, the holder of a position seat, or the position when the seat is vacant. */
export function memberName(intl: IntlShape, m: MemberResponse): string {
  if (m.kind === 'POSITION') return m.positionTitle ?? intl.formatMessage(fm.vacant)
  return m.person?.name ?? ''
}

/** Whether the signed-in user is limited as a secretary (manages members but is no administrator). */
export function secretaryOnly(forum: ForumResponse): boolean {
  return forum.canManageMembers && !forum.canManage
}

/** Whether the user may act on [row]: administrators on every row, secretaries not on chairs, secretaries or themselves. */
export function canActOn(forum: ForumResponse, row: MemberResponse, userId: string | undefined): boolean {
  if (!forum.canManageMembers) return false
  if (!secretaryOnly(forum)) return true
  return !ADMIN_ROLES.includes(row.role) && (row.kind !== 'PERSON' || row.person?.id !== userId)
}

const REASONS = [
  'SECRETARY_LIMIT',
  'MEMBER_ENDED',
  'NO_TERM',
  'SAME_ROLE',
  'TOO_EARLY',
  'NOT_UPCOMING',
  'KEY_FIXED',
] as const
const TERM_REASONS = ['ONE_CHAIR', 'ALREADY_MEMBER', 'FORUM_DISSOLVED'] as const

/** Field problems the forms show beside their fields, translated where the code is known. */
export function fieldProblems(intl: IntlShape, terms: TermValues, problem: unknown): Record<string, string> {
  if (!isProblem(problem) || !problem.errors?.length) return {}
  const known: Record<string, string> = {
    'key:FORMAT': intl.formatMessage(fm.keyFormat),
    'key:KEY_TAKEN': intl.formatMessage(fmt.keyTaken, terms),
    'code:FORMAT': intl.formatMessage(fm.codeFormat),
    'code:CODE_TAKEN': intl.formatMessage(fmt.codeTaken, terms),
    'typeId:TYPE_INACTIVE': intl.formatMessage(fm.typeInactive),
    'parentId:PARENT_CYCLE': intl.formatMessage(fmt.parentCycle, terms),
    'alternateUserId:SELF': intl.formatMessage(fm.SELF),
    'alternateUserId:unknown': intl.formatMessage(fm.unknownPerson),
    'userId:unknown': intl.formatMessage(fm.unknownPerson),
    'positionId:unknown': intl.formatMessage(fm.unknownPerson),
    'termEndsOn:BEFORE_START': intl.formatMessage(fm.BEFORE_START),
    'endedOn:BEFORE_START': intl.formatMessage(fm.BEFORE_START),
    'attributes:UNKNOWN_ATTRIBUTE': intl.formatMessage(fm.UNKNOWN_ATTRIBUTE),
  }
  return Object.fromEntries(problem.errors.map((e) => [e.field, known[`${e.field}:${e.code ?? ''}`] ?? e.message]))
}

/** One sentence for a refusal: a known reason, a lost race, or a generic failure. */
export function refusalMessage(intl: IntlShape, terms: TermValues, problem: unknown): string {
  if (!isProblem(problem)) return intl.formatMessage(fm.failed)
  const reason = REASONS.find((r) => r === problem.reason)
  if (reason) return intl.formatMessage(fm[reason])
  const termReason = TERM_REASONS.find((r) => r === problem.reason)
  if (termReason) return intl.formatMessage(fmt[termReason], terms)
  if (problem.status === 412 || problem.status === 409) return intl.formatMessage(fm.conflict)
  return intl.formatMessage(fm.failed)
}

/** A workflow key suggested from a name: "Board Risk Committee" → "board-risk-committee". */
export function suggestKey(name: string): string {
  return name
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^[^a-z]+/, '')
    .replace(/-+$/, '')
    .slice(0, 100)
}

const FORUM_QUERIES = new Set(['listForums', 'getForum', 'listForumMembers', 'getForumPolicy', 'listMyForums'])

/** Reloads every committee query after a change (lists, the page, its members, its policy and "My committees"). */
export function refreshForums(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({
    predicate: (query) => FORUM_QUERIES.has((query.queryKey[0] as { _id?: string } | undefined)?._id ?? ''),
  })
}

/** The type's attribute labels by key, for the quorum sentence. */
export function attributeNames(policy?: ForumPolicyResponse): Record<string, string> {
  return Object.fromEntries(
    (policy?.memberAttributes ?? []).flatMap((a): [string, string][] => (a.key ? [[a.key, a.label ?? a.key]] : [])),
  )
}

/** Attribute labels from the type, falling back to the key for an attribute the type no longer has. */
export function attributeLabels(member: MemberResponse, policy?: ForumPolicyResponse): string[] {
  const names = attributeNames(policy)
  return member.attributes.map((key) => names[key] ?? key)
}
