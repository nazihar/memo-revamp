import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MemoPage } from '@/features/memo/MemoPage'

const crumbs = defineMessages({
  page: { id: 'nav.memo', defaultMessage: 'Memo' },
})

/** A memo the user takes part in; others get "not found" from the API, whether it exists or not. */
export const Route = createFileRoute('/_shell/_signedIn/memos/$memoId')({
  component: MemoRoute,
  staticData: { crumb: crumbs.page },
})

function MemoRoute() {
  const { memoId } = Route.useParams()
  return <MemoPage key={memoId} memoId={memoId} />
}
