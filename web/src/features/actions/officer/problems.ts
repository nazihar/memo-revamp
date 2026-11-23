import type { IntlShape } from 'react-intl'
import { isProblem } from '@/api/http'
import type { TermValues } from '@/features/terms/terms'
import { actionRefusal, fieldText as ownerFieldText } from '../problems'
import { ao } from './messages'

/** An example date for "Please enter a date on or after today, for example 12/03/2027": a month from today, in the reader's form. */
export function exampleDate(intl: IntlShape): string {
  const d = new Date()
  d.setMonth(d.getMonth() + 1)
  return intl.formatDate(d, { day: '2-digit', month: '2-digit', year: 'numeric' })
}

/**
 * A field problem of the officers' endpoints in words (design §3, as built in T3–T8): raising, editing, the owner, the
 * colour, cancelling, reopening and holding back. Undefined when it has none of its own.
 */
export function officerFieldText(
  intl: IntlShape,
  terms: TermValues,
  field: string,
  code: string | undefined,
): string | undefined {
  const at = field.replace(/\/\d+$/, '').replace(/\/(userId|positionId)$/, '')
  const c = code ?? ''
  const byCode: Record<string, string | undefined> = {
    DUE_IN_PAST: intl.formatMessage(ao.fieldDueInPast, { example: exampleDate(intl) }),
    OWNER_NOT_CLEARED: intl.formatMessage(ao.fieldOwnerNotCleared),
    NOT_CLEARED: intl.formatMessage(ao.fieldNotCleared),
    RECUSED_NAMED: intl.formatMessage(ao.fieldRecusedNamed),
    SAME_OWNER: intl.formatMessage(ao.fieldSameOwner),
    DUPLICATE: intl.formatMessage(ao.fieldDuplicate),
    CLASSIFICATION: intl.formatMessage(ao.fieldClassification),
    CONDITION: intl.formatMessage(ao.fieldCondition),
    NOT_EDITABLE: intl.formatMessage(ao.fieldNotEditable),
    SUPERSEDED_BY: intl.formatMessage(ao.fieldSupersededBy),
    LENGTH: intl.formatMessage(ao.fieldTooLong),
  }
  const byField: Record<string, string | undefined> = {
    'title:REQUIRED': intl.formatMessage(ao.fieldRequiredTitle),
    'owner:REQUIRED': intl.formatMessage(ao.fieldRequiredOwner),
    'owner:SAME_PERSON': intl.formatMessage(ao.fieldSamePerson),
    'owner:unknown': intl.formatMessage(ao.fieldUnknownPerson),
    'contributors:SAME_PERSON': intl.formatMessage(ao.fieldSamePerson),
    'contributors:TOO_MANY': intl.formatMessage(ao.fieldContributorsTooMany),
    'contributors:unknown': intl.formatMessage(ao.fieldUnknownPerson),
    'dueDate:REQUIRED': intl.formatMessage(ao.fieldRequiredDue),
    'reason:REQUIRED': intl.formatMessage(ao.fieldRequiredReason),
    'kind:REQUIRED': intl.formatMessage(ao.fieldRequiredKind),
    'rag:REQUIRED': intl.formatMessage(ao.fieldRequiredRag),
    'decision:REQUIRED': intl.formatMessage(ao.fieldRequiredDecision),
    'onBehalfOf:REQUIRED': intl.formatMessage(ao.fieldRequiredHow),
    'supersededBy:REQUIRED': intl.formatMessage(ao.fieldSupersededBy),
    'supersededBy:SOURCE': intl.formatMessage(ao.fieldSupersededBy),
  }
  return byField[`${at}:${c}`] ?? byCode[c] ?? ownerFieldText(intl, terms, field, code)
}

/** The field a problem belongs to on the officers' forms: `owner/userId` is the owner, `contributors/2` the contributors. */
export function fieldOf(field: string): string {
  return field.replace(/\/\d+$/, '').replace(/\/(userId|positionId|kind)$/, '')
}

/** Field problems in words, by field; the first of a field is the one shown. */
export function officerFieldProblems(intl: IntlShape, terms: TermValues, problem: unknown): Record<string, string> {
  if (!isProblem(problem) || !problem.errors?.length) return {}
  const found: Record<string, string> = {}
  for (const e of problem.errors) {
    found[fieldOf(e.field)] ??= officerFieldText(intl, terms, e.field, e.code) ?? e.message
  }
  return found
}

/** A refusal reason the officers' endpoints have and the owner's side does not (T7's hold back and ask, T4's colour). */
const OWN = {
  RECUSED: ao.refusedRECUSED,
  NO_OVERRIDE: ao.refusedNO_OVERRIDE,
  NO_MEETING: ao.refusedNO_MEETING,
  ALREADY_HELD: ao.refusedALREADY_HELD,
  ALREADY_ASKED: ao.refusedALREADY_ASKED,
  COMMITTEE: ao.refusedCOMMITTEE,
} as const

/** One sentence for a refused officer request: its own reasons first, then the owner's side's (T11). */
export function officerRefusal(intl: IntlShape, terms: TermValues, problem: unknown): string {
  if (isProblem(problem)) {
    const reason = problem.reason as keyof typeof OWN | undefined
    if (reason && reason in OWN) return intl.formatMessage(OWN[reason], terms)
    if (problem.errors?.length) {
      return problem.errors.map((e) => officerFieldText(intl, terms, e.field, e.code) ?? e.message).join(' ')
    }
  }
  return actionRefusal(intl, terms, problem)
}

/** Why one draft of a bulk send, or one action of a bulk close, did not go: a problem code in words. */
export function reasonWords(intl: IntlShape, terms: TermValues, reason: string | null | undefined): string {
  const code = reason ?? ''
  if (code === 'NOT_VERIFIER') return intl.formatMessage(ao.closeNotVerifier)
  if (code === 'REQUIRED')
    return `${intl.formatMessage(ao.fieldRequiredOwner)} ${intl.formatMessage(ao.fieldRequiredDue)}`
  return (
    officerFieldText(intl, terms, '', code) ??
    officerRefusal(intl, terms, { type: 'about:blank', status: 409, title: code, reason: code })
  )
}
