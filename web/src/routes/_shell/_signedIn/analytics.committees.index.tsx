import { createFileRoute } from '@tanstack/react-router'
import { parseOverviewSearch } from '@/features/analytics/analytics-params'
import { OverviewPage } from '@/features/analytics/OverviewPage'

/** The overview per committee (flow AU); the period and a committee to narrow to are in the address. */
export const Route = createFileRoute('/_shell/_signedIn/analytics/committees/')({
  validateSearch: parseOverviewSearch,
  component: OverviewRoute,
})

function OverviewRoute() {
  return <OverviewPage search={Route.useSearch()} />
}
