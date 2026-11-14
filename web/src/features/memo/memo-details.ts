import { defineMessages, type IntlShape } from 'react-intl'
import type { MemoDetailView, MemoView } from '@/api/generated/types.gen'

export const dm = defineMessages({
  details: { id: 'memo.details', defaultMessage: 'Details' },
  stale: {
    id: 'memo.details.stale',
    defaultMessage: 'This memo type changed since the draft was written. Check the details before you submit.',
  },
  noLongerOnForm: { id: 'memo.details.noLongerOnForm', defaultMessage: 'No longer on this form' },
  numberedOnSubmit: { id: 'memo.numberedWhen.submit', defaultMessage: 'Unnumbered — numbered when submitted' },
  numberedOnApproval: {
    id: 'memo.numberedWhen.approval',
    defaultMessage: 'Unnumbered — numbered when finally approved',
  },
})

/** Statuses in which a memo without a number may still get one. */
const NUMBERABLE = new Set<MemoView['status']>(['DRAFT', 'RETURNED', 'IN_REVIEW'])

/**
 * The page header's "Unnumbered" line, with when the number comes (ADR-0044) while it still can; null once numbered.
 */
export function unnumberedNote(intl: IntlShape, memo: MemoView): string | null {
  if (memo.reference || !memo.numberedWhen || !NUMBERABLE.has(memo.status)) return null
  return intl.formatMessage(memo.numberedWhen === 'ON_APPROVAL' ? dm.numberedOnApproval : dm.numberedOnSubmit)
}

function isAmount(value: unknown): value is { value: string; currency: string } {
  return typeof value === 'object' && value !== null && 'value' in value && 'currency' in value
}

/** A detail as people read it: amounts with their currency, dates in the reader's locale, names for ids and keys. */
export function detailText(intl: IntlShape, detail: MemoDetailView): string {
  if (detail.display) return detail.display
  const value = detail.value
  switch (detail.type) {
    case 'AMOUNT':
      if (isAmount(value)) {
        const n = Number(value.value)
        return Number.isFinite(n)
          ? intl.formatNumber(n, { style: 'currency', currency: value.currency, currencyDisplay: 'code' })
          : `${value.currency} ${value.value}`
      }
      break
    case 'NUMBER': {
      const n = Number(value)
      if (Number.isFinite(n)) return intl.formatNumber(n, { maximumFractionDigits: 20 })
      break
    }
    case 'DATE':
      if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
        return intl.formatDate(`${value}T00:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' })
      }
      break
    default:
      break
  }
  if (Array.isArray(value)) return value.map(String).join(', ')
  return typeof value === 'string' ? value : JSON.stringify(value)
}
