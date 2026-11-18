import type { QueryClient } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import type { ForumResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import type { TermValues } from '@/features/terms/terms'
import type { Submission, SubmissionStatus } from './papers'
import { sm, smt } from './submission-messages'

/** What a secretary can do to a paper from its row menu or its page (flow M1). */
export type TriageAction = 'accept' | 'defer' | 'return' | 'decline' | 'refer' | 'cover'

/** The officers who see the queue (design E02 §4): the chair and secretaries in office, and `forums.manage`. */
export function seesPapers(forum: ForumResponse): boolean {
  return forum.canManage || forum.myRole === 'CHAIR' || forum.myRole === 'SECRETARY'
}

/** Who triages (design E02 §4): the secretaries in office and `forums.manage`; the chair reads only. */
export function triages(forum: ForumResponse): boolean {
  return forum.canManage || forum.myRole === 'SECRETARY'
}

const ACCEPTS: SubmissionStatus[] = ['QUEUED', 'DEFERRED']
const DEFERS: SubmissionStatus[] = ['QUEUED', 'ACCEPTED']
const OPEN: SubmissionStatus[] = ['QUEUED', 'ACCEPTED', 'DEFERRED']

/** The actions the server allows in the paper's status (`SubmissionTriage`); none once it is closed. */
export function actionsFor(s: Submission): TriageAction[] {
  if (!OPEN.includes(s.status)) return []
  return [
    ...(ACCEPTS.includes(s.status) ? (['accept'] as const) : []),
    ...(DEFERS.includes(s.status) ? (['defer'] as const) : []),
    'return',
    'decline',
    'refer',
    'cover',
  ]
}

/** Whole days since [instant], as the queue's "Waiting" column shows them. */
export function ageInDays(instant: string, now = Date.now()): number {
  return Math.max(0, Math.floor((now - new Date(instant).getTime()) / 86_400_000))
}

/** Waited past the type's triage days: the hourly sweep reminded the secretaries (design E02 §9). */
export function waitingTooLong(s: Submission): boolean {
  return s.status === 'QUEUED' && s.remindedAt != null
}

const PAPER_QUERIES = new Set(['listForumSubmissions', 'getForumSubmission', 'listMemoForumSubmissions', 'getMemo'])

/** Reloads every view of papers after a change: the queue, a paper's page and the memo's panel. */
export function refreshPapers(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({
    predicate: (query) => PAPER_QUERIES.has((query.queryKey[0] as { _id?: string } | undefined)?._id ?? ''),
  })
}

/** Field problems shown beside their fields: a missing reason, a date in the past, an unknown committee. */
export function triageFieldProblems(intl: IntlShape, terms: TermValues, problem: unknown): Record<string, string> {
  if (!isProblem(problem) || !problem.errors?.length) return {}
  const known: Record<string, string> = {
    'reason:REASON_REQUIRED': intl.formatMessage(sm.required),
    'targetDate:TARGET_IN_PAST': intl.formatMessage(sm.TARGET_IN_PAST),
    'forumId:unknown': intl.formatMessage(smt.unknownForum, terms),
  }
  return Object.fromEntries(problem.errors.map((e) => [e.field, known[`${e.field}:${e.code ?? ''}`] ?? e.message]))
}

/** One sentence for a refusal: the paper moved on, another committee is needed, a lost race, or a failure. */
export function triageRefusal(intl: IntlShape, terms: TermValues, problem: unknown): string {
  if (!isProblem(problem)) return intl.formatMessage(sm.failed)
  if (problem.reason === 'WRONG_STATE') return intl.formatMessage(sm.WRONG_STATE)
  if (problem.reason === 'SAME_FORUM') return intl.formatMessage(smt.SAME_FORUM, terms)
  if (problem.status === 412 || problem.status === 409) return intl.formatMessage(sm.conflict)
  return intl.formatMessage(sm.failed)
}
