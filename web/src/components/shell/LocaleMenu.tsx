import { Languages } from 'lucide-react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useLocale } from '@/i18n/LocaleProvider'
import { availableLocales, directionOf, isAppLocale, type AppLocale } from '@/i18n/locales'

const messages = defineMessages<{ label: NoMessageValues; trigger: { current: string } }>({
  label: { id: 'locale.label', defaultMessage: 'Language' },
  trigger: { id: 'locale.trigger', defaultMessage: 'Change language (current: {current})' },
})

/** Language names are shown in their own language (endonyms), so they are data, not translatable UI text. */
const ENDONYMS: Record<AppLocale, string> = {
  en: 'English',
  ar: 'العربية',
  'en-XA': '[Ƥşḗḗŭŭḓǿǿ]',
  'xx-LS': 'Pseudo (long)',
  'en-XB': 'Pseudo (bidi)',
}

export function LocaleMenu() {
  const intl = useIntl()
  const { locale, setLocale } = useLocale()
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label={intl.formatMessage(messages.trigger, { current: ENDONYMS[locale] })}
        >
          <Languages aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>{intl.formatMessage(messages.label)}</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={locale}
          onValueChange={(value) => {
            if (isAppLocale(value)) setLocale(value)
          }}
        >
          {availableLocales().map((l) => (
            <DropdownMenuRadioItem key={l} value={l} lang={l} dir={directionOf(l)}>
              {ENDONYMS[l]}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
