import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { NewMemoPage } from '@/features/memo/NewMemoPage'

const crumbs = defineMessages({
  page: { id: 'nav.newMemo', defaultMessage: 'New memo' },
})

/** Every active user may create memos (`memo.create`, held through the user role). */
export const Route = createFileRoute('/_shell/_signedIn/memos/new')({
  component: NewMemoPage,
  staticData: { crumb: crumbs.page },
})
