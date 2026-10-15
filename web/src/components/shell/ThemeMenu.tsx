import { Monitor, Moon, Sun } from 'lucide-react'
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
import { THEMES, useTheme, type Theme } from '@/theme/ThemeProvider'

/** Typed placeholders: a missing or misspelled value is a compile error. */
const messages = defineMessages<{
  label: NoMessageValues
  trigger: { current: string }
  light: NoMessageValues
  dark: NoMessageValues
  system: NoMessageValues
}>({
  label: { id: 'theme.label', defaultMessage: 'Theme' },
  trigger: { id: 'theme.trigger', defaultMessage: 'Change theme (current: {current})' },
  light: { id: 'theme.light', defaultMessage: 'Light' },
  dark: { id: 'theme.dark', defaultMessage: 'Dark' },
  system: { id: 'theme.system', defaultMessage: 'System' },
})

const icons = { light: Sun, dark: Moon, system: Monitor } as const

export function ThemeMenu() {
  const intl = useIntl()
  const { theme, setTheme } = useTheme()
  const Icon = icons[theme]
  const name = (t: Theme) => intl.formatMessage(messages[t])
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={intl.formatMessage(messages.trigger, { current: name(theme) })}>
          <Icon aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>{intl.formatMessage(messages.label)}</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={theme}
          onValueChange={(value) => {
            setTheme(value as Theme)
          }}
        >
          {THEMES.map((t) => (
            <DropdownMenuRadioItem key={t} value={t}>
              {name(t)}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
