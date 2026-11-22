import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ResolutionPage } from '@/features/resolutions/ResolutionPage'

const crumbs = defineMessages({
  page: { id: 'resolutions.crumb', defaultMessage: 'Resolution' },
})

/**
 * One resolution (Phase 4 E06 T10, flow AC2), where the register, the meeting page and the memo's note sheet link. The
 * API decides who may see it and how much: whoever sees its item's decision, and the readers of its memo.
 */
export const Route = createFileRoute('/_shell/_signedIn/resolutions/$resolutionId')({
  component: ResolutionRoute,
  staticData: { crumb: crumbs.page },
})

function ResolutionRoute() {
  const { resolutionId } = Route.useParams()
  return <ResolutionPage key={resolutionId} resolutionId={resolutionId} />
}
