import { defineMessages } from 'react-intl'

/**
 * The committee's decision as the memo shows it (Phase 4 E06 T10, flow AH): the note sheet's FORUM_DECIDED entry with
 * its resolution number and link, the corrected-decision note, and the line in the memo's committee panel. In the
 * `papers` catalogue part (loaded with `/memos` and `/committees`).
 */
export const pdm = defineMessages({
  openResolution: { id: 'papers.decision.openResolution', defaultMessage: 'Open the resolution' },
  corrected: { id: 'papers.decision.corrected', defaultMessage: 'The committee’s decision was corrected' },
})

export const pdmv = defineMessages<{
  resolution: { number: string; meeting: string }
  resolutionOnly: { number: string }
  decidedAt: { meeting: string; outcome: string; number: string }
  decidedAtNoNumber: { meeting: string; outcome: string }
}>({
  resolution: { id: 'papers.decision.resolution', defaultMessage: 'Resolution {number} · {meeting}' },
  resolutionOnly: { id: 'papers.decision.resolutionOnly', defaultMessage: 'Resolution {number}' },
  decidedAt: {
    id: 'papers.decision.decidedAt',
    defaultMessage:
      'Decided at {meeting} — {outcome, select, APPROVED {Approved} APPROVED_WITH_CONDITIONS {Approved with conditions} NOTED {Noted} REJECTED {Rejected} RETURNED {Returned} other {Decided}} — {number}',
  },
  decidedAtNoNumber: {
    id: 'papers.decision.decidedAtNoNumber',
    defaultMessage:
      'Decided at {meeting} — {outcome, select, APPROVED {Approved} APPROVED_WITH_CONDITIONS {Approved with conditions} NOTED {Noted} REJECTED {Rejected} RETURNED {Returned} other {Decided}}',
  },
})
