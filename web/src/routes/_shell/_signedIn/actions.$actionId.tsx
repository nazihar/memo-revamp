import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ActionPage } from '@/features/actions/ActionPage'

// The crumb lives here, not in the actions' messages: route options are in the first page's script (NFR P5).
const crumbs = defineMessages({
  action: { id: 'actions.crumb.action', defaultMessage: 'Action' },
})

/** One committee action (Phase 4 E07 T11, flow AL): the owner's, a contributor's, a member's or the verifier's view. */
export const Route = createFileRoute('/_shell/_signedIn/actions/$actionId')({
  component: ActionRoute,
  staticData: { crumb: crumbs.action },
})

function ActionRoute() {
  const { actionId } = Route.useParams()
  return <ActionPage key={actionId} actionId={actionId} />
}
