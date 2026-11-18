import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ForumPage, type ForumTab } from '@/features/forums/ForumPage'

const crumbs = defineMessages({
  page: { id: 'nav.forum', defaultMessage: '{Forum}' },
})

const TABS: ForumTab[] = ['members', 'meetings', 'submissions', 'history', 'policy']

/**
 * One committee: its members on a date, its meetings, its papers (officers), its history and its policy; a committee
 * the user may not see is not found. `?tab=meetings` opens the meetings, `?tab=submissions` the papers.
 */
export const Route = createFileRoute('/_shell/_signedIn/committees/$forumId')({
  validateSearch: (search: Record<string, unknown>): { tab?: ForumTab } => {
    const tab = TABS.find((t) => t === search.tab)
    return tab ? { tab } : {}
  },
  component: ForumRoute,
  staticData: { crumb: crumbs.page },
})

function ForumRoute() {
  const { forumId } = Route.useParams()
  const { tab } = Route.useSearch()
  return <ForumPage key={forumId} forumId={forumId} tab={tab} />
}
