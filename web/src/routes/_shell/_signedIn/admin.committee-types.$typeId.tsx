import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ForumTypePage } from '@/features/forum-types/ForumTypePage'

const crumbs = defineMessages({
  page: { id: 'nav.forumType', defaultMessage: '{Forum} type' },
})

/** One committee type and its versions; the version shown is in the address, so a link opens it (ADR-0043). */
export const Route = createFileRoute('/_shell/_signedIn/admin/committee-types/$typeId')({
  validateSearch: (search: Record<string, unknown>): { version?: number } =>
    typeof search.version === 'number' && Number.isInteger(search.version) ? { version: search.version } : {},
  component: ForumTypeRoute,
  staticData: { crumb: crumbs.page },
})

function ForumTypeRoute() {
  const { typeId } = Route.useParams()
  const { version } = Route.useSearch()
  return <ForumTypePage typeId={typeId} {...(version ? { version } : {})} />
}
