import { Link } from '@tanstack/react-router'
import type { MessageDescriptor } from 'react-intl'
import { useIntl } from 'react-intl'
import { adminItems } from '@/app/navigation'
import { PageHeader } from '@/components/page/PageHeader'
import { useMyAccess } from '@/features/access/access'
import { useSession, viewerOf } from '@/features/auth/session'
import { am } from './admin-messages'

const descriptions: Record<string, MessageDescriptor> = {
  users: am.cardUsers,
  roles: am.cardRoles,
  classifications: am.cardClassifications,
  email: am.cardEmail,
  security: am.cardSecurity,
  branding: am.cardBranding,
  language: am.cardLanguage,
  audit: am.cardAudit,
}

/** `/admin` (design E07 §7): one card per administration area the viewer may use. */
export function AdminHomePage() {
  const intl = useIntl()
  const session = useSession()
  const access = useMyAccess(session.data?.state === 'AUTHENTICATED')
  const items = adminItems(viewerOf(session.data, access.data))
  return (
    <section aria-labelledby="admin-title">
      <PageHeader
        title={<span id="admin-title">{intl.formatMessage(am.homeTitle)}</span>}
        description={intl.formatMessage(am.homeLead)}
      />
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((item) => {
          const Icon = item.icon
          const description = descriptions[item.id]
          return (
            <li key={item.id}>
              <Link
                to={item.to}
                className="flex h-full gap-3 rounded-lg border bg-card p-4 text-card-foreground hover:border-primary focus-visible:outline-2 focus-visible:outline-ring"
              >
                <Icon aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
                <span className="flex flex-col gap-1">
                  <span className="font-medium">{intl.formatMessage(item.label)}</span>
                  {description ? (
                    <span className="text-sm text-muted-foreground">{intl.formatMessage(description)}</span>
                  ) : null}
                </span>
              </Link>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
