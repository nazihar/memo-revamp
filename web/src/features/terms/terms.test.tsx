import { createIntl } from 'react-intl'
import { describe, expect, it } from 'vitest'
import ar from '@/i18n/messages/ar.json'
import { termValues } from './terms'

const standard = (locale: 'en' | 'ar') => () =>
  locale === 'en'
    ? { one: 'unit', other: 'units', gender: 'other' }
    : { one: 'وحدة', other: 'وحدات', gender: 'feminine' }

describe('terms (ADR-0032)', () => {
  it('uses the organisation’s words and derives the capitalised forms', () => {
    const values = termValues([{ term: 'unit', one: 'department', other: 'departments' }], standard('en'), 'en')
    expect(values).toMatchObject({ unit: 'department', units: 'departments', Unit: 'Department', Units: 'Departments' })
    expect(values).toMatchObject({ position: 'unit', positions: 'units' })
  })

  it('makes Arabic messages agree with the term’s gender', () => {
    const intl = createIntl({ locale: 'ar', messages: ar })
    const message = { id: 'org.addRoot', defaultMessage: '{unitGender, select, other {Add top {unit}}}' }

    const feminine = termValues([], standard('ar'), 'ar')
    const masculine = termValues(
      [{ term: 'unit', one: 'قسم', other: 'أقسام', gender: 'masculine' }],
      standard('ar'),
      'ar',
    )

    expect(intl.formatMessage(message, feminine)).toBe('إضافة وحدة عليا')
    expect(intl.formatMessage(message, masculine)).toBe('إضافة قسم أعلى')
  })
})
