import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { UserDetailPage } from '@/features/access/UserDetailPage'

const crumbs = defineMessages({
  page: { id: 'nav.user', defaultMessage: 'User' },
})

/** The menu shows it only to permission holders; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/users_/$userId')({
  component: UserRoute,
  staticData: { crumb: crumbs.page },
})

function UserRoute() {
  const { userId } = Route.useParams()
  return <UserDetailPage userId={userId} />
}
