import { createFileRoute } from '@tanstack/react-router'
import { InvitationPage } from '@/features/auth/InvitationPage'

export const Route = createFileRoute('/_auth/invitation')({
  component: InvitationPage,
})
