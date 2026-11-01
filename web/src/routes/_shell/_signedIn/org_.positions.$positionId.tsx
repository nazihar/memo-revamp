import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { PositionPage } from '@/features/org/PositionPage'

const crumbs = defineMessages({
  position: { id: 'nav.position', defaultMessage: 'Position' },
})

export const Route = createFileRoute('/_shell/_signedIn/org_/positions/$positionId')({
  component: PositionRoute,
  staticData: { crumb: crumbs.position },
})

function PositionRoute() {
  const { positionId } = Route.useParams()
  return <PositionPage positionId={positionId} />
}
