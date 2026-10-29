import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { LogOut, ShieldCheck } from 'lucide-react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { logout } from '@/api/generated/sdk.gen'
import { useSession } from './session'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

const messages = defineMessages<{
  trigger: { name: string }
  security: NoMessageValues
  signOut: NoMessageValues
  signIn: NoMessageValues
}>({
  trigger: { id: 'account.trigger', defaultMessage: 'Account: {name}' },
  security: { id: 'account.security', defaultMessage: 'My security' },
  signOut: { id: 'account.signOut', defaultMessage: 'Sign out' },
  signIn: { id: 'account.signIn', defaultMessage: 'Sign in' },
})

/** The account menu of the top bar (E03 design §11); a sign-in link for anonymous visitors (the status page). */
export function AccountMenu() {
  const intl = useIntl()
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const user = session.data?.state === 'AUTHENTICATED' ? session.data.user : undefined

  if (!session.data) return null
  if (!user) {
    return (
      <Button asChild variant="outline">
        <Link to="/login">{intl.formatMessage(messages.signIn)}</Link>
      </Button>
    )
  }

  const signOut = async () => {
    await logout({ throwOnError: false })
    queryClient.clear()
    await navigate({ to: '/login' })
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label={intl.formatMessage(messages.trigger, { name: user.displayName })}
        >
          <span
            aria-hidden="true"
            className="flex size-8 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground"
          >
            {user.displayName.slice(0, 1)}
          </span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel className="flex flex-col">
          <span>{user.displayName}</span>
          <span className="text-xs font-normal text-muted-foreground">{user.email}</span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => void navigate({ to: '/me/security' })}>
          <ShieldCheck aria-hidden="true" />
          {intl.formatMessage(messages.security)}
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void signOut()}>
          <LogOut aria-hidden="true" />
          {intl.formatMessage(messages.signOut)}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
