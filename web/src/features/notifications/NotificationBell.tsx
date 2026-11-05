import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Bell } from 'lucide-react'
import { useIntl } from 'react-intl'
import { getMyUnreadCountOptions } from '@/api/generated/@tanstack/react-query.gen'
import { useSession } from '@/features/auth/session'
import { nm, nmv } from './notification-messages'

/** How often the badge asks for the unread count while the page is visible (1a polls; real time arrives in Phase 5). */
export const UNREAD_POLL_MS = 60_000

/**
 * The top bar's notifications entry (E01 slot, design E06 §8): a link to the notifications page with an unread badge.
 * A link rather than a popover, so it is one large target that works the same by mouse, keyboard and screen reader.
 */
export function NotificationBell() {
  const intl = useIntl()
  const session = useSession()
  const signedIn = session.data?.state === 'AUTHENTICATED'
  const unread = useQuery({
    ...getMyUnreadCountOptions(),
    enabled: signedIn,
    refetchInterval: UNREAD_POLL_MS,
    refetchIntervalInBackground: false,
  })
  if (!signedIn) return null
  const count = unread.data?.count ?? 0
  return (
    <Link
      to="/notifications"
      aria-label={intl.formatMessage(nmv.bell, { count })}
      className="relative inline-flex size-10 items-center justify-center rounded-md hover:bg-accent hover:text-accent-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
    >
      <Bell aria-hidden="true" className="size-5" />
      {count > 0 ? (
        <span
          aria-hidden="true"
          className="absolute -end-0.5 -top-0.5 min-w-5 rounded-full bg-destructive px-1 text-center text-xs font-semibold leading-5 text-destructive-foreground"
        >
          {count > 9 ? intl.formatMessage(nm.bellMore) : count}
        </span>
      ) : null}
    </Link>
  )
}
