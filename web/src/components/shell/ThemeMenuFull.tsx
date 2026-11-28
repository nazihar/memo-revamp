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
import { THEMES, useTheme, type Theme } from '@/theme/ThemeProvider'
import type { DeferredMenuProps } from './DeferredMenu'
import { themeIcons, themeMessages as messages } from './theme-menu'

/** The theme menu itself, loaded by [ThemeMenu] when first wanted. */
export default function ThemeMenuFull({ defaultOpen }: DeferredMenuProps) {
  const intl = useIntl()
  const { theme, setTheme } = useTheme()
  const Icon = themeIcons[theme]
  const name = (t: Theme) => intl.formatMessage(messages[t])
  return (
    <DropdownMenu defaultOpen={defaultOpen}>
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
