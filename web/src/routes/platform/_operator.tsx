import { createFileRoute, Outlet, redirect } from '@tanstack/react-router'
import { platformSessionQuery } from '@/features/platform/platform-session'

/** Console pages need a complete operator session; anything else goes to the operator sign-in. */
export const Route = createFileRoute('/platform/_operator')({
  beforeLoad: async ({ context }) => {
    const session = await context.queryClient.query(platformSessionQuery()).catch(() => undefined)
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's redirect() is designed to be thrown
    if (session?.state !== 'AUTHENTICATED') throw redirect({ to: '/platform/login' })
  },
  component: Outlet,
})
