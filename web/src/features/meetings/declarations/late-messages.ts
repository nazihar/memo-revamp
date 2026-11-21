import { defineMessages } from 'react-intl'

/**
 * Texts of "Late papers for you to decide" on the meeting page (Phase 4 E04, design §1.7 as built): the late items and
 * papers the viewer decides, for the chair and for the vice-chair when the chair has declared an interest. In the
 * `declarations` catalogue part, which loads on `/meetings` pages.
 */
export const lm = defineMessages({
  title: { id: 'declarations.late.title', defaultMessage: 'Late papers for you to decide' },
  lead: {
    id: 'declarations.late.lead',
    defaultMessage: 'These came in after the paper deadline. The members see them only if you agree.',
  },
  restrictedPaper: { id: 'declarations.late.restrictedPaper', defaultMessage: 'A restricted paper' },
  include: { id: 'declarations.late.include', defaultMessage: 'Agree to include it' },
  decline: { id: 'declarations.late.decline', defaultMessage: 'Not this meeting' },
  cancel: { id: 'declarations.late.cancel', defaultMessage: 'Cancel' },
  declineItemText: {
    id: 'declarations.late.declineItemText',
    defaultMessage:
      'The item is taken off the agenda and its paper goes back to the committee’s list, ready for a later meeting.',
  },
  declinePaperText: {
    id: 'declarations.late.declinePaperText',
    defaultMessage: 'The paper is taken off the item and not published.',
  },
  stale: {
    id: 'declarations.late.stale',
    defaultMessage: 'The agenda changed just now. The list below is up to date again: please decide once more.',
  },
  recused: {
    id: 'declarations.late.recused',
    defaultMessage:
      'You have declared an interest in this item, so you cannot decide it. A secretary records the committee’s answer.',
  },
  forbidden: {
    id: 'declarations.late.forbidden',
    defaultMessage:
      'Only the chair decides this, or the vice-chair when the chair has declared an interest in the item.',
  },
  wrongState: { id: 'declarations.late.wrongState', defaultMessage: 'This is no longer waiting for a decision.' },
  frozen: {
    id: 'declarations.late.frozen',
    defaultMessage: 'The meeting has been held or cancelled: its agenda no longer changes.',
  },
  notFound: { id: 'declarations.late.notFound', defaultMessage: 'This paper is no longer on the agenda.' },
  rateLimited: {
    id: 'declarations.late.rateLimited',
    defaultMessage: 'Too many changes at once. Wait a minute and try again.',
  },
  failed: { id: 'declarations.late.failed', defaultMessage: 'Your decision could not be saved. Please try again.' },
})

/** Texts with values. */
export const lmv = defineMessages<{
  paperFor: { paper: string; item: string }
  dueOn: { date: string }
  includeNamed: { name: string }
  declineNamed: { name: string }
  declineTitle: { name: string }
  agreed: { name: string }
  declined: { name: string }
}>({
  paperFor: { id: 'declarations.late.v.paperFor', defaultMessage: '{paper}, for {item}' },
  dueOn: { id: 'declarations.late.v.dueOn', defaultMessage: 'Papers were due on {date}.' },
  includeNamed: { id: 'declarations.late.v.includeNamed', defaultMessage: 'Agree to include {name}' },
  declineNamed: { id: 'declarations.late.v.declineNamed', defaultMessage: 'Not this meeting: {name}' },
  declineTitle: { id: 'declarations.late.v.declineTitle', defaultMessage: 'Not this meeting: {name}?' },
  agreed: {
    id: 'declarations.late.v.agreed',
    defaultMessage: '{name} is agreed and will be published as a late paper.',
  },
  declined: { id: 'declarations.late.v.declined', defaultMessage: '{name} is not for this meeting.' },
})
