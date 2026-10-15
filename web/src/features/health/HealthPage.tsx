import { useQuery } from '@tanstack/react-query'
import { getHealthOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { Health } from '@/api/generated/types.gen'
import { t, tDynamic } from '@/i18n/messages'

/** The backend answers 503 with the same body when the platform is DOWN; show it instead of a generic error. */
function isHealth(value: unknown): value is Health {
  return typeof value === 'object' && value !== null && 'status' in value && 'components' in value
}

export function HealthPage() {
  const query = useQuery({ ...getHealthOptions(), refetchInterval: 15_000 })
  const health = query.data ?? (isHealth(query.error) ? query.error : undefined)

  return (
    <section aria-labelledby="health-title">
      <h1 id="health-title">{t('health.title')}</h1>
      {query.isPending && <p role="status">{t('health.loading')}</p>}
      {!query.isPending && !health && <p role="alert">{t('health.unreachable')}</p>}
      {health && <HealthDetails health={health} />}
    </section>
  )
}

function HealthDetails({ health }: { health: Health }) {
  return (
    <>
      {/* Status is always text, never color alone (WCAG 1.4.1). */}
      <p data-testid="overall-status" data-status={health.status}>
        {tDynamic(`health.status.${health.status}`, health.status)}
      </p>
      <p>
        {t('health.checkedAt')}: <time dateTime={health.checkedAt}>{new Date(health.checkedAt).toLocaleString()}</time>
      </p>
      <h2>{t('health.components')}</h2>
      <dl>
        {health.components.map((c) => (
          <div key={c.name}>
            <dt>{tDynamic(`health.component.${c.name}`, c.name)}</dt>
            <dd data-status={c.status}>{tDynamic(`health.status.${c.status}`, c.status)}</dd>
          </div>
        ))}
      </dl>
    </>
  )
}
