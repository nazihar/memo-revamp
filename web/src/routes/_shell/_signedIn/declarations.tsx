import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { RegisterPage } from '@/features/meetings/declarations/RegisterPage'

const crumbs = defineMessages({
  page: { id: 'nav.declarations', defaultMessage: 'Declarations of interest' },
})

/**
 * The register of declarations of interest across committees (Phase 4 E04 T8, flow U). `?forumId=` opens it on one
 * committee (the meeting's Declarations tab links here). The API answers 403 to anyone without oversight access or an
 * officer's seat, and the page says so.
 */
export const Route = createFileRoute('/_shell/_signedIn/declarations')({
  validateSearch: (search: Record<string, unknown>): { forumId?: string } =>
    typeof search.forumId === 'string' && search.forumId ? { forumId: search.forumId } : {},
  component: DeclarationsRoute,
  staticData: { crumb: crumbs.page },
})

function DeclarationsRoute() {
  const { forumId } = Route.useSearch()
  return <RegisterPage key={forumId ?? ''} forumId={forumId} />
}
