import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { BatchPage } from '@/features/imports/BatchPage'
import { STEPS, type StepId } from '@/features/imports/batch-params'

const crumbs = defineMessages({
  page: { id: 'nav.importBatch', defaultMessage: 'Import batch' },
})

/** One import batch and its steps; the step shown is in the address (`?step=`), so a link or a reload opens it. */
export const Route = createFileRoute('/_shell/_signedIn/admin/imports/$batchId')({
  validateSearch: (search: Record<string, unknown>): { step?: StepId } =>
    STEPS.includes(search.step as StepId) ? { step: search.step as StepId } : {},
  component: BatchRoute,
  staticData: { crumb: crumbs.page },
})

function BatchRoute() {
  const { batchId } = Route.useParams()
  const { step } = Route.useSearch()
  return <BatchPage batchId={batchId} {...(step ? { step } : {})} />
}
