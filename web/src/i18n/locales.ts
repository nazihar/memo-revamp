import type { MessageFormatElement } from 'react-intl'
import type { CataloguePart } from './catalogue-parts'

export type Messages = Record<string, string> | Record<string, MessageFormatElement[]>

/** Locales offered to users. `en` is the source locale; `ar` proves RTL until the pilot's languages arrive (Phase 3). */
export const PRODUCTION_LOCALES = ['en', 'ar'] as const
/**
 * Pseudo-locales, never offered in production builds: en-XA accented + bracketed (hard-coded strings, clipping),
 * xx-LS ~40% longer (layout overflow), en-XB bidi (RTL handling).
 */
export const PSEUDO_LOCALES = ['en-XA', 'xx-LS', 'en-XB'] as const

export type AppLocale = (typeof PRODUCTION_LOCALES)[number] | (typeof PSEUDO_LOCALES)[number]

export const DEFAULT_LOCALE: AppLocale = 'en'

export function availableLocales(): readonly AppLocale[] {
  return import.meta.env.PROD && import.meta.env.MODE !== 'e2e'
    ? PRODUCTION_LOCALES
    : [...PRODUCTION_LOCALES, ...PSEUDO_LOCALES]
}

export function isAppLocale(value: string | null): value is AppLocale {
  return value !== null && (availableLocales() as readonly string[]).includes(value)
}

const RTL_LANGUAGES = new Set(['ar', 'he', 'fa', 'ur'])

export function directionOf(locale: string): 'ltr' | 'rtl' {
  if (locale === 'en-XB') return 'rtl' // bidi pseudo-locale renders mirrored
  return RTL_LANGUAGES.has(locale.split('-')[0] ?? '') ? 'rtl' : 'ltr'
}

/** react-intl needs a real BCP 47 locale for Intl formatting; pseudo-locales format like English. */
export function formattingLocale(locale: AppLocale): string {
  return (PSEUDO_LOCALES as readonly string[]).includes(locale) ? 'en' : locale
}

/**
 * The catalogue parts of each locale (catalogue-parts.json), each its own chunk, loaded on demand. English needs none:
 * its messages are the source messages in the code, so react-intl shows them as they are.
 */
const loaders: Record<CataloguePart, Record<string, () => Promise<Messages>>> = {
  core: import.meta.glob<Messages>('./messages/*.json', { query: { part: 'core' }, import: 'default' }),
  admin: import.meta.glob<Messages>('./messages/*.json', { query: { part: 'admin' }, import: 'default' }),
  platform: import.meta.glob<Messages>('./messages/*.json', { query: { part: 'platform' }, import: 'default' }),
  patterns: import.meta.glob<Messages>('./messages/*.json', { query: { part: 'patterns' }, import: 'default' }),
}

/** [part] of [locale]'s messages; empty for English (the source locale). */
export async function loadMessages(locale: AppLocale, part: CataloguePart): Promise<Messages> {
  if (locale === DEFAULT_LOCALE) return {}
  const loader = loaders[part][`./messages/${locale}.json`]
  return loader ? loader() : {}
}
