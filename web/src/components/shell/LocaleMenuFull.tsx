import { Languages } from 'lucide-react'
import { useIntl } from 'react-intl'
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
import { availableLocales, directionOf, isAppLocale } from '@/i18n/locales'
import type { DeferredMenuProps } from './DeferredMenu'
import { ENDONYMS, localeMessages as messages } from './locale-menu'

/** The language menu itself, loaded by [LocaleMenu] when first wanted. */
export default function LocaleMenuFull({ defaultOpen }: DeferredMenuProps) {
  const intl = useIntl()
  const { locale, setLocale } = useLocale()
  return (
    <DropdownMenu defaultOpen={defaultOpen}>
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
