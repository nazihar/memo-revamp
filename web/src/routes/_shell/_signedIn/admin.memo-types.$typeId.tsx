import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MemoTypePage } from '@/features/memo-types/MemoTypePage'

const crumbs = defineMessages({
  page: { id: 'nav.memoType', defaultMessage: 'Memo type' },
})

/** One memo type and its versions; the version shown is in the address, so a link opens it (ADR-0043). */
export const Route = createFileRoute('/_shell/_signedIn/admin/memo-types/$typeId')({
  validateSearch: (search: Record<string, unknown>): { version?: number } =>
    typeof search.version === 'number' && Number.isInteger(search.version) ? { version: search.version } : {},
  component: MemoTypeRoute,
  staticData: { crumb: crumbs.page },
})

function MemoTypeRoute() {
  const { typeId } = Route.useParams()
  const { version } = Route.useSearch()
  return <MemoTypePage typeId={typeId} {...(version ? { version } : {})} />
}
