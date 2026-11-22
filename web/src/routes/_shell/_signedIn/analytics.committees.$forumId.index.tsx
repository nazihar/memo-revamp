import { createFileRoute } from '@tanstack/react-router'
import { parsePeriodSearch } from '@/features/analytics/analytics-params'
import { DetailPage } from '@/features/analytics/DetailPage'

/** A committee's detail (flow AW); a figure of the overview opens it at its section (`#timing`, `#actions`…). */
export const Route = createFileRoute('/_shell/_signedIn/analytics/committees/$forumId/')({
  validateSearch: parsePeriodSearch,
  component: DetailRoute,
})

function DetailRoute() {
  const { forumId } = Route.useParams()
  return <DetailPage key={forumId} forumId={forumId} search={Route.useSearch()} />
}
