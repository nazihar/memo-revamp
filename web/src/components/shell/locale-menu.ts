import { defineMessages, type NoMessageValues } from 'react-intl'
import type { AppLocale } from '@/i18n/locales'

export const localeMessages = defineMessages<{ label: NoMessageValues; trigger: { current: string } }>({
  label: { id: 'locale.label', defaultMessage: 'Language' },
  trigger: { id: 'locale.trigger', defaultMessage: 'Change language (current: {current})' },
})

/** Language names are shown in their own language (endonyms), so they are data, not translatable UI text. */
export const ENDONYMS: Record<AppLocale, string> = {
  en: 'English',
  ar: 'العربية',
  'en-XA': '[Ƥşḗḗŭŭḓǿǿ]',
  'xx-LS': 'Pseudo (long)',
  'en-XB': 'Pseudo (bidi)',
}
