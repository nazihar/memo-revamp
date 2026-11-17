import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ImportsPage } from '@/features/imports/ImportsPage'

const crumbs = defineMessages({
  page: { id: 'nav.imports', defaultMessage: 'Import' },
})

/** The menu shows it to holders of `memo.import`; the API answers 403 to anyone else (design E06 §10). */
export const Route = createFileRoute('/_shell/_signedIn/admin/imports/')({
  component: ImportsPage,
  staticData: { crumb: crumbs.page },
})
