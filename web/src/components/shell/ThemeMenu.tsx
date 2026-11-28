import { lazy } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { useTheme } from '@/theme/ThemeProvider'
import { DeferredMenu } from './DeferredMenu'
import { themeIcons, themeMessages } from './theme-menu'

const load = () => import('./ThemeMenuFull')
const ThemeMenuFull = lazy(load)

/** The theme menu of the top bar; its menu loads when first wanted ([DeferredMenu]). */
export function ThemeMenu() {
  const intl = useIntl()
  const { theme } = useTheme()
  const Icon = themeIcons[theme]
  return (
    <DeferredMenu menu={ThemeMenuFull} preload={load}>
      <Button
        variant="ghost"
        size="icon"
        aria-label={intl.formatMessage(themeMessages.trigger, { current: intl.formatMessage(themeMessages[theme]) })}
      >
        <Icon aria-hidden="true" />
      </Button>
    </DeferredMenu>
  )
}
