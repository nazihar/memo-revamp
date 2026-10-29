import { createFileRoute, Outlet, redirect } from '@tanstack/react-router'
import { sessionQuery } from '@/features/auth/session'

/**
 * Route guard (E03 design §11): pages below need a complete session. Anything else goes to the sign-in step it still
 * needs, and comes back to the page it asked for. The server enforces access on every call regardless.
 */
export const Route = createFileRoute('/_shell/_signedIn')({
  beforeLoad: async ({ context, location }) => {
    const session = await context.queryClient.query(sessionQuery())
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's redirect() is designed to be thrown
    if (session.state === 'MFA_ENROLMENT_REQUIRED') throw redirect({ to: '/login/enrol-mfa' })
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- as above
    if (session.state !== 'AUTHENTICATED') throw redirect({ to: '/login', search: { next: location.href } })
  },
  component: Outlet,
})
