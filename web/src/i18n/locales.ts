import type { MessageFormatElement } from 'react-intl'
import en from './messages/en.json'

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

/** Messages per locale. English is bundled; other locales are code-split and loaded on demand. */
export async function loadMessages(locale: AppLocale): Promise<Messages> {
  switch (locale) {
    case 'en':
      return en
    case 'ar':
      return (await import('./messages/ar.json')).default
    case 'en-XA':
      return (await import('./messages/en-XA.json')).default
    case 'xx-LS':
      return (await import('./messages/xx-LS.json')).default
    case 'en-XB':
      return (await import('./messages/en-XB.json')).default
  }
}

export const sourceMessages: Messages = en
