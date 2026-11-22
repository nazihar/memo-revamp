import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { parsePeriodSearch } from '@/features/analytics/analytics-params'
import { MemberPage } from '@/features/analytics/MemberPage'

const crumbs = defineMessages({
  page: { id: 'nav.analyticsMember', defaultMessage: 'Member' },
})

/** A seat holder's meetings of the period with their attendance (design E08 §2.6), each linking to its meeting. */
export const Route = createFileRoute('/_shell/_signedIn/analytics/committees/$forumId/attendance/$userId')({
  validateSearch: parsePeriodSearch,
  component: MemberRoute,
  staticData: { crumb: crumbs.page },
})

function MemberRoute() {
  const { forumId, userId } = Route.useParams()
  return <MemberPage key={`${forumId}-${userId}`} forumId={forumId} userId={userId} search={Route.useSearch()} />
}
