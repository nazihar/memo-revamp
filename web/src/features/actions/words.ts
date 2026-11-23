import type { IntlShape } from 'react-intl'
import type { ActionOwnerResponse, ActionRagResponse, ActionResponse, ActionSummary } from '@/api/generated/types.gen'
import { am } from './messages'

export type RagWord = ActionRagResponse['word']
export type RagColour = ActionRagResponse['colour']
export type ActionStatus = ActionResponse['status']
export type Confidence = NonNullable<ActionResponse['confidence']>
export type How = 'PHONE' | 'EMAIL' | 'IN_PERSON' | 'MEETING'
export type CancelKind = NonNullable<ActionResponse['cancelKind']>

/** A date without a time ("2026-11-28") in words, on no clock: the server's dates are days in the tenant's zone. */
export function day(intl: IntlShape, date: string, style: 'long' | 'medium' = 'long'): string {
  return intl.formatDate(`${date}T12:00:00Z`, { dateStyle: style, timeZone: 'UTC' })
}

/** An instant's date in the reader's zone. */
export function dayOf(intl: IntlShape, instant: string, style: 'long' | 'medium' = 'medium'): string {
  return intl.formatDate(instant, { dateStyle: style })
}

/** The colour's word (design §1.5): never shown without it. */
export function ragWord(intl: IntlShape, word: RagWord): string {
  return intl.formatMessage(am[`rag${word}`])
}

export function statusWord(intl: IntlShape, status: ActionStatus): string {
  return intl.formatMessage(am[`status${status}`])
}

export function confidenceWord(intl: IntlShape, confidence: Confidence): string {
  return intl.formatMessage(am[`confidence${confidence}`])
}

export function howWord(intl: IntlShape, how: How): string {
  return intl.formatMessage(am[`how${how}`])
}

export function cancelWord(intl: IntlShape, kind: CancelKind): string {
  return intl.formatMessage(am[`cancel${kind}`])
}

/**
 * "due 28 November 2026 (originally 14 November 2026, revised once)" (design §1.7, principle 4): where the date started is
 * always shown once it moved.
 */
export function dueWords(
  intl: IntlShape,
  a: Pick<ActionSummary, 'dueDate' | 'originalDueDate' | 'dueRevisions'>,
  style: 'long' | 'medium' = 'medium',
): string {
  if (!a.dueDate) return intl.formatMessage(am.noDue)
  const date = day(intl, a.dueDate, style)
  if (a.dueRevisions > 0 && a.originalDueDate && a.originalDueDate !== a.dueDate) {
    return intl.formatMessage(am.dueRevised, {
      date,
      original: day(intl, a.originalDueDate, style),
      count: a.dueRevisions,
    })
  }
  return intl.formatMessage(am.due, { date })
}

/**
 * The owner in words (principle 1, design §1.3): "Head of Treasury (A. Karim)" for a position and its holder, the person's
 * name, or "A. Karim, acting for B. Rahman" when someone else holds a person's action.
 */
export function ownerWords(intl: IntlShape, owner: ActionOwnerResponse | null | undefined): string | undefined {
  if (!owner) return undefined
  if (owner.kind === 'POSITION' && owner.position) {
    return owner.holder
      ? intl.formatMessage(am.ownerPositionHolder, { position: owner.position.title, name: owner.holder.name })
      : intl.formatMessage(am.ownerWaiting, { position: owner.position.title })
  }
  const person = owner.person?.name
  if (owner.holder && owner.person && owner.holder.id !== owner.person.id) {
    return intl.formatMessage(am.ownerActingFor, { name: owner.holder.name, owner: owner.person.name })
  }
  return person ?? owner.holder?.name
}

/** "BRC A9/2026", or "Draft action" before it is issued. */
export function referenceWords(intl: IntlShape, reference: string | null | undefined): string {
  return reference ?? intl.formatMessage(am.draftReference)
}
