import { Languages } from 'lucide-react'
import { lazy } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { useLocale } from '@/i18n/LocaleProvider'
import { DeferredMenu } from './DeferredMenu'
import { ENDONYMS, localeMessages } from './locale-menu'

const load = () => import('./LocaleMenuFull')
const LocaleMenuFull = lazy(load)

/** The language menu of the top bar; its menu loads when first wanted ([DeferredMenu]). */
export function LocaleMenu() {
  const intl = useIntl()
  const { locale } = useLocale()
  return (
    <DeferredMenu menu={LocaleMenuFull} preload={load}>
      <Button
        variant="ghost"
        size="icon"
        aria-label={intl.formatMessage(localeMessages.trigger, { current: ENDONYMS[locale] })}
      >
        <Languages aria-hidden="true" />
      </Button>
    </DeferredMenu>
  )
}
