import { createFileRoute } from '@tanstack/react-router'
import { ActionPage } from '@/features/actions/ActionPage'
import { am } from '@/features/actions/messages'

/** One committee action (Phase 4 E07 T11, flow AL): the owner's, a contributor's, a member's or the verifier's view. */
export const Route = createFileRoute('/_shell/_signedIn/actions/$actionId')({
  component: ActionRoute,
  staticData: { crumb: am.crumbAction },
})

function ActionRoute() {
  const { actionId } = Route.useParams()
  return <ActionPage key={actionId} actionId={actionId} />
}
