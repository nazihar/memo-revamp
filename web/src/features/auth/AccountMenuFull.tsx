import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { BellRing, CalendarDays, LogOut, ShieldCheck, UsersRound } from 'lucide-react'
import { useIntl } from 'react-intl'
import { logout } from '@/api/generated/sdk.gen'
import type { DeferredMenuProps } from '@/components/shell/DeferredMenu'
import { useTerms } from '@/features/terms/terms'
import { accountMessages as messages } from './account-menu'
import { AccountInitial } from './AccountInitial'
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

/** The account menu itself, loaded by [AccountMenu] when first wanted, for a signed-in person. */
export default function AccountMenuFull({ defaultOpen }: DeferredMenuProps) {
  const intl = useIntl()
  const terms = useTerms()
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const user = session.data?.state === 'AUTHENTICATED' ? session.data.user : undefined
  if (!user) return null

  const signOut = async () => {
    await logout({ throwOnError: false })
    queryClient.clear()
    await navigate({ to: '/login' })
  }

  return (
    <DropdownMenu defaultOpen={defaultOpen}>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label={intl.formatMessage(messages.trigger, { name: user.displayName })}
        >
          <AccountInitial name={user.displayName} />
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
        <DropdownMenuItem onSelect={() => void navigate({ to: '/me/committees' })}>
          <UsersRound aria-hidden="true" />
          {intl.formatMessage(messages.forums, terms)}
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void navigate({ to: '/me/meetings' })}>
          <CalendarDays aria-hidden="true" />
          {intl.formatMessage(messages.meetings)}
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void navigate({ to: '/me/notifications' })}>
          <BellRing aria-hidden="true" />
          {intl.formatMessage(messages.notifications)}
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void signOut()}>
          <LogOut aria-hidden="true" />
          {intl.formatMessage(messages.signOut)}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
