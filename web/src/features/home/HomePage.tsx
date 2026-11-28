import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Activity, ShieldCheck, Users, UsersRound } from 'lucide-react'
import { lazy, Suspense, type ReactNode } from 'react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { listMyForumsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { useSession } from '@/features/auth/session'
import { upcoming, useMyMeetings } from '@/features/meetings/mine'
import { useTerms, type TermValues } from '@/features/terms/terms'
import { PageHeader } from '@/components/page/PageHeader'

const messages = defineMessages<{
  title: { name: string }
  lead: NoMessageValues
  security: NoMessageValues
  securityText: NoMessageValues
  users: NoMessageValues
  usersText: NoMessageValues
  status: NoMessageValues
  statusText: NoMessageValues
  forums: TermValues
}>({
  title: { id: 'home.title', defaultMessage: 'Welcome, {name}' },
  lead: {
    id: 'home.lead',
    defaultMessage: 'Memos, meetings and approvals arrive here in the coming releases.',
  },
  security: { id: 'home.security', defaultMessage: 'My security' },
  securityText: { id: 'home.security.text', defaultMessage: 'Password, two-step sign-in and signed-in devices.' },
  users: { id: 'home.users', defaultMessage: 'Users' },
  usersText: { id: 'home.users.text', defaultMessage: 'Invite people and manage their accounts.' },
  status: { id: 'home.status', defaultMessage: 'Platform status' },
  statusText: { id: 'home.status.text', defaultMessage: 'Whether every part of the platform is working.' },
  forums: { id: 'home.forums', defaultMessage: 'My {forums}' },
})

// The card, its replies and their messages load only for someone with a meeting to show: they are not in the first page's
// script of everyone else (NFR P5).
const MyMeetingsCard = lazy(() => import('@/features/meetings/MyMeetings').then((m) => ({ default: m.MyMeetingsCard })))

/** "My meetings" on the home page (flow N3), once the person has a meeting to come or under way. */
function MeetingsSlot() {
  const mine = useMyMeetings()
  if (!upcoming(mine.data ?? []).length) return null
  return (
    <Suspense fallback={null}>
      <MyMeetingsCard />
    </Suspense>
  )
}

/** Landing page after sign-in; the work queues of later phases replace the shortcuts. */
export function HomePage() {
  const intl = useIntl()
  const session = useSession()
  const user = session.data?.user
  const terms = useTerms()
  const mine = useQuery({ ...listMyForumsOptions(), enabled: session.data?.state === 'AUTHENTICATED' })
  // Flow L1: the card shows while the user sits on a committee, or will.
  const sitting = (mine.data ?? []).filter((m) => m.state === 'IN_OFFICE' || m.state === 'UPCOMING')
  return (
    <section className="max-w-3xl">
      <PageHeader
        title={intl.formatMessage(messages.title, { name: user?.displayName ?? '' })}
        description={intl.formatMessage(messages.lead)}
      />
      <MeetingsSlot />
      <ul className="grid gap-3 sm:grid-cols-2">
        {sitting.length ? (
          <Shortcut
            to="/me/committees"
            icon={<UsersRound aria-hidden="true" />}
            title={intl.formatMessage(messages.forums, terms)}
          >
            {intl.formatList(sitting.map((m) => m.name))}
          </Shortcut>
        ) : null}
        <Shortcut
          to="/me/security"
          icon={<ShieldCheck aria-hidden="true" />}
          title={intl.formatMessage(messages.security)}
        >
          {intl.formatMessage(messages.securityText)}
        </Shortcut>
        {user?.tenantAdmin ? (
          <Shortcut to="/admin/users" icon={<Users aria-hidden="true" />} title={intl.formatMessage(messages.users)}>
            {intl.formatMessage(messages.usersText)}
          </Shortcut>
        ) : null}
        <Shortcut to="/status" icon={<Activity aria-hidden="true" />} title={intl.formatMessage(messages.status)}>
          {intl.formatMessage(messages.statusText)}
        </Shortcut>
      </ul>
    </section>
  )
}

function Shortcut({ to, icon, title, children }: { to: string; icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <li>
      <Link
        to={to}
        className="flex h-full gap-3 rounded-lg border bg-card p-4 text-card-foreground hover:bg-accent hover:text-accent-foreground [&_svg]:mt-0.5 [&_svg]:size-5 [&_svg]:shrink-0"
      >
        {icon}
        <span className="flex flex-col gap-1">
          <span className="font-semibold">{title}</span>
          <span className="text-sm text-muted-foreground">{children}</span>
        </span>
      </Link>
    </li>
  )
}
