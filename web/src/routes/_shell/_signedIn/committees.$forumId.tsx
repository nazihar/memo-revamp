import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { FORUM_TABS, type ForumTab } from '@/features/forums/forum-tabs'
import { ForumPage } from '@/features/forums/ForumPage'

const crumbs = defineMessages({
  page: { id: 'nav.forum', defaultMessage: '{Forum}' },
})

/**
 * One committee: its members on a date, its meetings, its papers (officers), its history and its policy; a committee
 * the user may not see is not found. `?tab=meetings` opens the meetings, `?tab=submissions` the papers; the tab chosen
 * replaces the address's, so a reload, Back and a change of language keep it.
 */
export const Route = createFileRoute('/_shell/_signedIn/committees/$forumId')({
  validateSearch: (search: Record<string, unknown>): { tab?: ForumTab } => {
    const tab = FORUM_TABS.find((t) => t === search.tab)
    return tab ? { tab } : {}
  },
  component: ForumRoute,
  staticData: { crumb: crumbs.page },
})

function ForumRoute() {
  const { forumId } = Route.useParams()
  const { tab } = Route.useSearch()
  const navigate = Route.useNavigate()
  return (
    <ForumPage
      key={forumId}
      forumId={forumId}
      tab={tab}
      onTab={(next) => void navigate({ search: next === 'members' ? {} : { tab: next }, replace: true })}
    />
  )
}
