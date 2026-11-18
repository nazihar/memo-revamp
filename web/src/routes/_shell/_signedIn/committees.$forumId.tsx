import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ForumPage } from '@/features/forums/ForumPage'

const crumbs = defineMessages({
  page: { id: 'nav.forum', defaultMessage: '{Forum}' },
})

/** One committee: its members on a date, its history and its policy; a committee the user may not see is not found. */
export const Route = createFileRoute('/_shell/_signedIn/committees/$forumId')({
  component: ForumRoute,
  staticData: { crumb: crumbs.page },
})

function ForumRoute() {
  const { forumId } = Route.useParams()
  return <ForumPage key={forumId} forumId={forumId} />
}
