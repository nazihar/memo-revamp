import { Link } from '@tanstack/react-router'
import { lazy } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { DeferredMenu } from '@/components/shell/DeferredMenu'
import { accountMessages as messages } from './account-menu'
import { AccountInitial } from './AccountInitial'
import { useSession } from './session'

const load = () => import('./AccountMenuFull')
const AccountMenuFull = lazy(load)

/**
 * The account menu of the top bar (E03 design §11); a sign-in link for anonymous visitors (the status page). The menu
 * loads when first wanted ([DeferredMenu]).
 */
export function AccountMenu() {
  const intl = useIntl()
  const session = useSession()
  const user = session.data?.state === 'AUTHENTICATED' ? session.data.user : undefined

  if (!session.data) return null
  if (!user) {
    return (
      <Button asChild variant="outline">
        <Link to="/login">{intl.formatMessage(messages.signIn)}</Link>
      </Button>
    )
  }
  return (
    <DeferredMenu menu={AccountMenuFull} preload={load}>
      <Button variant="ghost" size="icon" aria-label={intl.formatMessage(messages.trigger, { name: user.displayName })}>
        <AccountInitial name={user.displayName} />
      </Button>
    </DeferredMenu>
  )
}
