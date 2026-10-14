import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { IntlProvider, ReactIntlErrorCode } from 'react-intl'
import { PREFERENCE_KEYS, readPreference, writePreference } from '@/lib/preferences'
import {
  DEFAULT_LOCALE,
  directionOf,
  formattingLocale,
  isAppLocale,
  loadMessages,
  sourceMessages,
  type AppLocale,
  type Messages,
} from './locales'

interface LocaleContextValue {
  locale: AppLocale
  setLocale: (locale: AppLocale) => void
}

const LocaleContext = createContext<LocaleContextValue | null>(null)

export function useLocale(): LocaleContextValue {
  const ctx = useContext(LocaleContext)
  if (!ctx) throw new Error('useLocale must be used inside <LocaleProvider>')
  return ctx
}

function initialLocale(): AppLocale {
  const saved = readPreference(PREFERENCE_KEYS.locale)
  if (isAppLocale(saved)) return saved
  const browser = navigator.language.split('-')[0] ?? ''
  return isAppLocale(browser) ? browser : DEFAULT_LOCALE
}

/** Provides react-intl and keeps `<html lang dir>` in sync with the chosen locale (ADR-0010). */
export function LocaleProvider({ children, locale: forced }: { children: ReactNode; locale?: AppLocale }) {
  const [locale, setLocaleState] = useState<AppLocale>(() => forced ?? initialLocale())
  const [messages, setMessages] = useState<{ locale: AppLocale; messages: Messages }>({
    locale: DEFAULT_LOCALE,
    messages: sourceMessages,
  })

  useEffect(() => {
    let cancelled = false
    void loadMessages(locale).then((loaded) => {
      if (!cancelled) setMessages({ locale, messages: loaded })
    })
    document.documentElement.lang = locale
    document.documentElement.dir = directionOf(locale)
    return () => {
      cancelled = true
    }
  }, [locale])

  const setLocale = useCallback((next: AppLocale) => {
    writePreference(PREFERENCE_KEYS.locale, next)
    setLocaleState(next)
  }, [])

  const value = useMemo(() => ({ locale, setLocale }), [locale, setLocale])

  return (
    <LocaleContext.Provider value={value}>
      <IntlProvider
        locale={formattingLocale(messages.locale)}
        messages={messages.messages}
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
