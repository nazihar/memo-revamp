import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { IntlProvider, ReactIntlErrorCode } from 'react-intl'
import { PREFERENCE_KEYS, readPreference, writePreference } from '@/lib/preferences'
import type { CataloguePart } from './catalogue-parts'
import {
  DEFAULT_LOCALE,
  directionOf,
  formattingLocale,
  isAppLocale,
  loadMessages,
  type AppLocale,
  type Messages,
} from './locales'

interface LocaleContextValue {
  locale: AppLocale
  setLocale: (locale: AppLocale) => void
  /** The parts of the current locale's catalogue loaded so far. */
  loaded: ReadonlySet<CataloguePart>
  /** Asks for [part] of the current locale's catalogue (and of every locale chosen later). */
  request: (part: CataloguePart) => void
}

const ALL_PARTS: ReadonlySet<CataloguePart> = new Set(['core', 'admin', 'builder', 'memoAdmin', 'platform', 'patterns'])

const LocaleContext = createContext<LocaleContextValue | null>(null)

export function useLocale(): Pick<LocaleContextValue, 'locale' | 'setLocale'> {
  const ctx = useContext(LocaleContext)
  if (!ctx) throw new Error('useLocale must be used inside <LocaleProvider>')
  return ctx
}

/** Loads [part] of the catalogue for the pages that need it; true once its messages are there (E03 T10). */
/** Whether all of [parts] have loaded; asks for the missing ones. */
export function useCatalogueParts(parts: readonly CataloguePart[]): boolean {
  const ctx = useContext(LocaleContext)
  if (!ctx) throw new Error('useCatalogueParts must be used inside <LocaleProvider>')
  const { request } = ctx
  const key = parts.join(',')
  useEffect(() => {
    for (const part of key.split(',')) if (part) request(part as CataloguePart)
  }, [request, key])
  return parts.every((part) => ctx.loaded.has(part))
}

export function useCataloguePart(part: CataloguePart): boolean {
  const ctx = useContext(LocaleContext)
  if (!ctx) throw new Error('useCataloguePart must be used inside <LocaleProvider>')
  const { request } = ctx
  useEffect(() => {
    request(part)
  }, [request, part])
  return ctx.loaded.has(part)
}

function initialLocale(): AppLocale {
  const saved = readPreference(PREFERENCE_KEYS.locale)
  if (isAppLocale(saved)) return saved
  const browser = navigator.language.split('-')[0] ?? ''
  return isAppLocale(browser) ? browser : DEFAULT_LOCALE
}

interface Catalogue {
  locale: AppLocale
  parts: Partial<Record<CataloguePart, Messages>>
}

/**
 * Provides react-intl and keeps `<html lang dir>` in sync with the chosen locale (ADR-0010). The catalogue loads in
 * parts (catalogue-parts.json): core for every page, the others when a page under their routes asks for them.
 */
export function LocaleProvider({ children, locale: forced }: { children: ReactNode; locale?: AppLocale }) {
  const [locale, setLocaleState] = useState<AppLocale>(() => forced ?? initialLocale())
  const [wanted, setWanted] = useState<ReadonlySet<CataloguePart>>(() => new Set(['core']))
  const [catalogue, setCatalogue] = useState<Catalogue>({ locale: DEFAULT_LOCALE, parts: {} })

  useEffect(() => {
    document.documentElement.lang = locale
    document.documentElement.dir = directionOf(locale)
  }, [locale])

  useEffect(() => {
    let cancelled = false
    if (locale === DEFAULT_LOCALE) return
    const missing = [...wanted].filter((part) => catalogue.locale !== locale || !(part in catalogue.parts))
    if (missing.length === 0) return
    void Promise.all(missing.map(async (part) => [part, await loadMessages(locale, part)] as const)).then((loaded) => {
      if (cancelled) return
      setCatalogue((current) => ({
        locale,
        parts: { ...(current.locale === locale ? current.parts : {}), ...Object.fromEntries(loaded) },
      }))
    })
    return () => {
      cancelled = true
    }
  }, [locale, wanted, catalogue])

  const setLocale = useCallback((next: AppLocale) => {
    writePreference(PREFERENCE_KEYS.locale, next)
    setLocaleState(next)
  }, [])

  const request = useCallback((part: CataloguePart) => {
    setWanted((current) => (current.has(part) ? current : new Set([...current, part])))
  }, [])

  // English is the source locale: its messages are in the code, so every part is there from the start.
  const current = catalogue.locale === locale
  const loaded = useMemo(
    () =>
      locale === DEFAULT_LOCALE
        ? ALL_PARTS
        : new Set<CataloguePart>(current ? (Object.keys(catalogue.parts) as CataloguePart[]) : []),
    [locale, current, catalogue],
  )
  const messages = useMemo(
    () => Object.assign({}, ...Object.values(current ? catalogue.parts : {})) as Messages,
    [current, catalogue],
  )
  const value = useMemo(() => ({ locale, setLocale, loaded, request }), [locale, setLocale, loaded, request])

  return (
    <LocaleContext.Provider value={value}>
      <IntlProvider
        locale={formattingLocale(current ? locale : DEFAULT_LOCALE)}
        messages={messages}
        defaultLocale={DEFAULT_LOCALE}
        onError={(err) => {
          // Missing translations fall back to the source message; everything else is a bug worth surfacing in dev.
          if (err.code !== ReactIntlErrorCode.MISSING_TRANSLATION && import.meta.env.DEV) console.error(err)
        }}
      >
        {children}
      </IntlProvider>
    </LocaleContext.Provider>
  )
}
