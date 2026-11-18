import { ftt } from './messages'
import type { OutcomeKind } from './settings'

/** What each kind means, translated; "Referred" names the organisation's word for a committee. */
export const meaningLabels = {
  APPROVED: ftt.APPROVED,
  APPROVED_WITH_CONDITIONS: ftt.APPROVED_WITH_CONDITIONS,
  REJECTED: ftt.REJECTED,
  DEFERRED: ftt.DEFERRED,
  RETURNED: ftt.RETURNED,
  REFERRED: ftt.REFERRED,
  NOTED: ftt.NOTED,
  WITHDRAWN: ftt.WITHDRAWN,
} satisfies Record<OutcomeKind, unknown>

export const effects = {
  APPROVED: ftt.APPROVED_effect,
  APPROVED_WITH_CONDITIONS: ftt.APPROVED_WITH_CONDITIONS_effect,
  REJECTED: ftt.REJECTED_effect,
  DEFERRED: ftt.DEFERRED_effect,
  RETURNED: ftt.RETURNED_effect,
  REFERRED: ftt.REFERRED_effect,
  NOTED: ftt.NOTED_effect,
  WITHDRAWN: ftt.WITHDRAWN_effect,
} satisfies Record<OutcomeKind, unknown>
