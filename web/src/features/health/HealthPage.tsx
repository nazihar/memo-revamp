import { useQuery } from '@tanstack/react-query'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { getHealthOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { Health } from '@/api/generated/types.gen'
import { StatusBadge } from '@/components/StatusBadge'

type NoValues = NoMessageValues

const messages = defineMessages<{
  title: NoValues
  loading: NoValues
  unreachable: NoValues
  overall: NoValues
  components: NoValues
  checkedAt: { time: string }
  database: NoValues
  objectStorage: NoValues
  documentServer: NoValues
  malwareScanner: NoValues
  mail: NoValues
}>({
  title: { id: 'health.title', defaultMessage: 'Platform status' },
  loading: { id: 'health.loading', defaultMessage: 'Checking platform status…' },
  unreachable: { id: 'health.unreachable', defaultMessage: 'The platform could not be reached.' },
  overall: { id: 'health.overall', defaultMessage: 'Overall' },
  components: { id: 'health.components', defaultMessage: 'Components' },
  checkedAt: { id: 'health.checkedAt', defaultMessage: 'Last checked {time}' },
  database: { id: 'health.component.database', defaultMessage: 'Database' },
  objectStorage: { id: 'health.component.objectStorage', defaultMessage: 'Object storage' },
  documentServer: { id: 'health.component.documentServer', defaultMessage: 'Document server' },
  malwareScanner: { id: 'health.component.malwareScanner', defaultMessage: 'Malware scanner' },
  mail: { id: 'health.component.mail', defaultMessage: 'Mail' },
})

const COMPONENT_NAMES = ['database', 'objectStorage', 'documentServer', 'malwareScanner', 'mail'] as const
type ComponentName = (typeof COMPONENT_NAMES)[number]

/** Unknown component names (a newer backend) are shown as-is rather than failing. */
function isComponentName(name: string): name is ComponentName {
  return (COMPONENT_NAMES as readonly string[]).includes(name)
}

/** The backend answers 503 with the same body when the platform is DOWN; show it instead of a generic error. */
function isHealth(value: unknown): value is Health {
  return typeof value === 'object' && value !== null && 'status' in value && 'components' in value
}

export function HealthPage() {
  const intl = useIntl()
  const query = useQuery({ ...getHealthOptions(), refetchInterval: 15_000 })
  const health = query.data ?? (isHealth(query.error) ? query.error : undefined)

  return (
    <section aria-labelledby="health-title" className="max-w-2xl space-y-6">
      <h1 id="health-title" className="text-2xl font-semibold">
        {intl.formatMessage(messages.title)}
      </h1>
      {query.isPending && (
        <p role="status" className="text-muted-foreground">
          {intl.formatMessage(messages.loading)}
        </p>
      )}
      {!query.isPending && !health && (
        <p role="alert" className="text-destructive">
          {intl.formatMessage(messages.unreachable)}
        </p>
      )}
      {health && <HealthDetails health={health} />}
    </section>
  )
}

function HealthDetails({ health }: { health: Health }) {
  const intl = useIntl()
  return (
    <div className="space-y-6 rounded-lg border bg-card p-6 text-card-foreground">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="flex items-center gap-2 text-lg">
          <span>{intl.formatMessage(messages.overall)}</span>
          <StatusBadge status={health.status} />
        </p>
        <p className="text-sm text-muted-foreground">
          <time dateTime={health.checkedAt}>
            {intl.formatMessage(messages.checkedAt, {
              time: intl.formatDate(health.checkedAt, { dateStyle: 'medium', timeStyle: 'medium' }),
            })}
          </time>
        </p>
      </div>
      <div>
        <h2 className="mb-2 text-base font-semibold">{intl.formatMessage(messages.components)}</h2>
        <dl className="divide-y">
          {health.components.map((c) => (
            <div key={c.name} className="flex min-h-11 items-center justify-between gap-4 py-2">
              <dt>{isComponentName(c.name) ? intl.formatMessage(messages[c.name]) : c.name}</dt>
              <dd>
                <StatusBadge status={c.status} />
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  )
}
