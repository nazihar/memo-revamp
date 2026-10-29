import { createFileRoute, redirect } from '@tanstack/react-router'
import { EnrolMfaPage } from '@/features/auth/EnrolMfaPage'
import { sessionQuery } from '@/features/auth/session'

/** First enrolment during sign-in, or a new device from a complete session (the API refuses anything else). */
export const Route = createFileRoute('/_auth/login_/enrol-mfa')({
  beforeLoad: async ({ context }) => {
    const session = await context.queryClient.query(sessionQuery())
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's redirect() is designed to be thrown
    if (session.state === 'ANONYMOUS') throw redirect({ to: '/login' })
  },
  component: EnrolMfaPage,
})
