import { useQuery } from '@tanstack/react-query'
import { defineMessages, useIntl, type MessageDescriptor } from 'react-intl'
import { getTermsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { Term } from '@/api/generated/types.gen'
import { useSession } from '@/features/auth/session'
import { useLocale } from '@/i18n/LocaleProvider'

export const TERMS = ['unit', 'position', 'memo'] as const
export type TermKey = (typeof TERMS)[number]

/**
 * The standard words (ADR-0032), translated like any message: singular and plural as used mid-sentence, and the
 * grammatical gender where the language has one (`masculine`, `feminine`; English says `other`).
 */
export const termDefaults: Record<
  TermKey,
  { one: MessageDescriptor; other: MessageDescriptor; gender: MessageDescriptor }
> = {
  unit: defineMessages({
    one: { id: 'term.unit.one', defaultMessage: 'unit' },
    other: { id: 'term.unit.other', defaultMessage: 'units' },
    gender: { id: 'term.unit.gender', defaultMessage: 'other' },
  }),
  position: defineMessages({
    one: { id: 'term.position.one', defaultMessage: 'position' },
    other: { id: 'term.position.other', defaultMessage: 'positions' },
    gender: { id: 'term.position.gender', defaultMessage: 'other' },
  }),
  memo: defineMessages({
    one: { id: 'term.memo.one', defaultMessage: 'memo' },
    other: { id: 'term.memo.other', defaultMessage: 'memos' },
    gender: { id: 'term.memo.gender', defaultMessage: 'other' },
  }),
}

/** The values a message with terms receives: `{unit}`, `{units}`, `{Unit}`, `{Units}`, `{unitGender}`, and so on. */
export type TermValues = Record<string, string>

function capitalise(word: string, locale: string): string {
  const first = word.slice(0, 1)
  return first.toLocaleUpperCase(locale) + word.slice(1)
}

/** Merges the organisation's overrides for the current language over the standard words. */
export function termValues(
  overrides: readonly Term[],
  defaults: (term: TermKey) => { one: string; other: string; gender: string },
  locale: string,
): TermValues {
  return Object.fromEntries(
    TERMS.flatMap((term) => {
      const override = overrides.find((o) => o.term === term)
      const standard = defaults(term)
      const one = override?.one ?? standard.one
      const other = override?.other ?? standard.other
      const gender = override ? (override.gender ?? 'other') : standard.gender
      return [
        [term, one],
        [`${term}s`, other],
        [capitalise(term, 'en'), capitalise(one, locale)],
        [`${capitalise(term, 'en')}s`, capitalise(other, locale)],
        [`${term}Gender`, gender],
      ]
    }),
  )
}

/**
 * The organisation's words for the current language (ADR-0032), for messages that take terms as arguments. Before
 * sign-in, and while loading, the standard words.
 */
export function useTerms(): TermValues {
  const intl = useIntl()
  const { locale } = useLocale()
  const language = locale.split('-')[0] === 'ar' ? 'ar' : 'en'
  const session = useSession()
  const terms = useQuery({
    ...getTermsOptions({ query: { locale: language } }),
    enabled: session.data?.state === 'AUTHENTICATED',
    staleTime: 5 * 60_000,
  })
  return termValues(
    terms.data?.terms ?? [],
    (term) => ({
      one: intl.formatMessage(termDefaults[term].one),
      other: intl.formatMessage(termDefaults[term].other),
      gender: intl.formatMessage(termDefaults[term].gender),
    }),
    intl.locale,
  )
}
