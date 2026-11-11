import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { DocumentEditorPage } from '@/features/memo/DocumentEditorPage'

const crumbs = defineMessages({
  page: { id: 'nav.memoDocument', defaultMessage: 'Document' },
})

/** The memo's document in the browser editor (ADR-0037); the API decides whether this user may edit or only read it. */
export const Route = createFileRoute('/_shell/_signedIn/memos/$memoId_/document')({
  validateSearch: (search: Record<string, unknown>): { mode?: 'edit' | 'view' } =>
    search.mode === 'edit' || search.mode === 'view' ? { mode: search.mode } : {},
  component: DocumentRoute,
  staticData: { crumb: crumbs.page },
})

function DocumentRoute() {
  const { memoId } = Route.useParams()
  const { mode } = Route.useSearch()
  return <DocumentEditorPage key={`${memoId}-${mode ?? 'view'}`} memoId={memoId} mode={mode ?? 'view'} />
}
