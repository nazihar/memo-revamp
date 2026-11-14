import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MemoTypesPage } from '@/features/memo-types/MemoTypesPage'

const crumbs = defineMessages({
  page: { id: 'nav.memoTypes', defaultMessage: 'Memo types' },
})

/** The menu shows it to holders of `memo.types.manage` or `memo.types.approve`; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/memo-types/')({
  component: MemoTypesPage,
  staticData: { crumb: crumbs.page },
})
