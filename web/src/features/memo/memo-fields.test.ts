import { createIntl } from 'react-intl'
import { describe, expect, it } from 'vitest'

import { isolated, referenceOf } from './memo-fields'

describe('referenceOf (ADR-0044: memos are numbered at submit or at final approval)', () => {
  const intl = createIntl({ locale: 'en', messages: {} })

  it('shows a number set apart from the surrounding text', () => {
    expect(referenceOf(intl, 'CR/HO-CR/2026-27/0001')).toBe(isolated('CR/HO-CR/2026-27/0001'))
  })

  it('says "Unnumbered" while a memo has no number', () => {
    expect(referenceOf(intl, null)).toBe('Unnumbered')
    expect(referenceOf(intl, undefined)).toBe('Unnumbered')
  })
})
