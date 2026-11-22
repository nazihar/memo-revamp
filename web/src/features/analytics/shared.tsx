import { Link } from '@tanstack/react-router'
import { ArrowRight } from 'lucide-react'
import type { ReactNode } from 'react'
import { useIntl } from 'react-intl'
import type { CommitteeDetailResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { useTerms } from '@/features/terms/terms'
import { am, amt } from './messages'
import { LINK } from './words'

/** What a failed analytics request means for the reader: no scope (403), a committee outside it (404), a bad period. */
export function AnalyticsProblem({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const status = isProblem(error) ? error.status : undefined
  if (status === 403) return <EmptyState title={intl.formatMessage(amt.noScope, terms)} />
  if (status === 404) {
    return (
      <EmptyState
        title={intl.formatMessage(amt.notFound, terms)}
        action={
          <Link to="/analytics/committees" className={LINK}>
            {intl.formatMessage(amt.toOverview, terms)}
            <Arrow />
          </Link>
        }
      />
    )
  }
  if (status === 422 && isProblem(error)) {
    const backwards = error.errors?.some((e) => e.field === 'to' && e.code === 'RANGE')
    return (
      <p role="alert" className="rounded-md border border-destructive/40 bg-card p-4 text-sm font-medium">
        {intl.formatMessage(backwards ? am.periodBackwards : am.periodTooLong)}
      </p>
    )
  }
  return <ErrorState onRetry={onRetry} />
}

/** Loading, the problem, or the page once the query has its data. */
export function Loaded<T>({
  query,
  children,
}: {
  query: { isPending: boolean; isError: boolean; error: unknown; data: T | undefined; refetch: () => unknown }
  children: (data: T) => ReactNode
}) {
  if (query.isPending) return <LoadingState />
  if (query.isError || query.data === undefined) {
    return (
      <AnalyticsProblem
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  }
  return <>{children(query.data)}</>
}

/** A figure link's arrow, turned round in right-to-left languages. */
export function Arrow() {
  return <ArrowRight aria-hidden="true" className="size-4 shrink-0 rtl:rotate-180" />
}

export type Detail = CommitteeDetailResponse
