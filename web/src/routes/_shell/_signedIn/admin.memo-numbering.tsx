import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { NumberingPage } from '@/features/memo-types/NumberingPage'

const crumbs = defineMessages({
  page: { id: 'nav.memoNumbering', defaultMessage: 'Memo numbering' },
})

/** Series, counters and registers (ADR-0044): memo-type administrators, reviewers and auditors. */
export const Route = createFileRoute('/_shell/_signedIn/admin/memo-numbering')({
  component: NumberingPage,
  staticData: { crumb: crumbs.page },
})
