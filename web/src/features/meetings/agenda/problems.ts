import type { IntlShape } from 'react-intl'
import { isProblem } from '@/api/http'
import type { TermValues } from '@/features/terms/terms'
import { allItems, type Draft } from './draft'
import { ap, apt, apv } from './messages'

/** Refusals of the agenda endpoints that have their own sentence (design E03 §3, as built in T2–T4). */
const REASONS = [
  'FROZEN',
  'NO_AGENDA',
  'ONE_LEVEL',
  'ONE_PLACEMENT',
  'SUBMISSION_STATE',
  'CHAIR_AGREEMENT_REQUIRED',
  'NOTHING_TO_PUBLISH',
  'MEETING_NOT_SCHEDULED',
  'SECTION_NOT_EMPTY',
  'ALREADY_IN_PACK',
  'WRONG_KIND',
  'AGENDA_STARTED',
  'ALREADY_AGREED',
  'WRONG_STATE',
] as const

/** One sentence for a refused change: a known reason, a lost race (412), no right, too many changes, or a failure. */
export function agendaRefusal(intl: IntlShape, terms: TermValues, problem: unknown, draft?: Draft): string {
  if (!isProblem(problem)) return intl.formatMessage(ap.failed)
  const reason = problem.reason
  if (reason === 'PAPER_DEADLINE_PASSED') return intl.formatMessage(apt.PAPER_DEADLINE_PASSED, terms)
  if (reason === 'NO_CHAIR') return intl.formatMessage(apt.NO_CHAIR, terms)
  if (reason === 'PAPER_NOT_READABLE') {
    const ids = (problem as { paperIds?: string[] }).paperIds ?? []
    const titles = draft
      ? allItems(draft)
          .flatMap((i) => i.papers)
          .filter((p) => ids.includes(p.id))
          .map((p) => p.title)
      : []
    return intl.formatMessage(apv.PAPER_NOT_READABLE, { titles: intl.formatList(titles), count: titles.length })
  }
  const known = REASONS.find((r) => r === reason)
  if (known) return intl.formatMessage(ap[known])
  if (problem.status === 412) return intl.formatMessage(ap.conflict)
  if (problem.status === 403) return intl.formatMessage(ap.forbidden)
  if (problem.status === 429) return intl.formatMessage(ap.rateLimited)
  if (problem.status === 404) return intl.formatMessage(ap.notFound)
  if (problem.errors?.length)
    return problem.errors.map((e) => fieldText(intl, terms, e.field, e.code, e.message)).join(' ')
  return intl.formatMessage(ap.failed)
}

function fieldText(
  intl: IntlShape,
  terms: TermValues,
  field: string,
  code: string | undefined,
  fallback: string,
): string {
  const byField: Record<string, string | undefined> = {
    'minutes:RANGE': intl.formatMessage(ap.minutesRange),
    'minutes:ONE_LEVEL': intl.formatMessage(ap.minutesSubItem),
    'note:NOTE_REQUIRED': intl.formatMessage(ap.noteRequired),
    'text:LENGTH': intl.formatMessage(ap.commentLength),
    'restrictedTo:REQUIRED': intl.formatMessage(ap.restrictionEmpty),
    'restrictedTo:RESTRICTION_AUDIENCE': intl.formatMessage(ap.restrictionAudience),
    'restrictedTo:CLASSIFICATION': intl.formatMessage(ap.notCleared),
    'attendees:CLASSIFICATION': intl.formatMessage(ap.notCleared),
    'attendees:unknown': intl.formatMessage(ap.unknownPerson),
    'classificationId:CLASSIFICATION': intl.formatMessage(ap.levelRange),
    'presenters:FORMAT': intl.formatMessage(ap.presenterFormat),
    'standingKind:duplicate': intl.formatMessage(ap.standingDuplicate),
    'meetingId:unknown': intl.formatMessage(ap.copyUnknown),
    'meetingId:SAME_MEETING': intl.formatMessage(ap.copySame),
    'meetingId:NO_AGENDA': intl.formatMessage(ap.copyNoAgenda),
    'sectionId:REQUIRED': intl.formatMessage(ap.sectionFirst),
    'submissionId:CLASSIFICATION': intl.formatMessage(ap.submissionAbove),
    'fileId:unknown': intl.formatMessage(ap.fileUnknown),
    'fileId:TOO_MANY': intl.formatMessage(ap.tooManyPapers),
    'as:NOT_IN_AUDIENCE': intl.formatMessage(apt.notInAudience, terms),
  }
  const byCode: Record<string, string | undefined> = {
    REQUIRED: intl.formatMessage(ap.required),
    LENGTH: intl.formatMessage(ap.tooLong),
    RANGE: intl.formatMessage(ap.range),
    TOO_MANY: intl.formatMessage(ap.tooMany),
    NOTE_REQUIRED: intl.formatMessage(ap.noteRequired),
    CLASSIFICATION: intl.formatMessage(ap.notCleared),
  }
  return byField[`${field}:${code ?? ''}`] ?? byCode[code ?? ''] ?? fallback
}

/** Field problems in words, by field, for the forms (the first problem of a field is the one shown). */
export function agendaFieldProblems(intl: IntlShape, terms: TermValues, problem: unknown): Record<string, string> {
  if (!isProblem(problem) || !problem.errors?.length) return {}
  const found: Record<string, string> = {}
  for (const e of problem.errors) found[e.field] ??= fieldText(intl, terms, e.field, e.code, e.message)
  return found
}
