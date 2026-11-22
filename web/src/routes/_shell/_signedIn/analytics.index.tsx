import { createFileRoute, redirect } from '@tanstack/react-router'

/** `/analytics` opens the committee analytics overview, the only analytics there are (Phase 4 E08 T7). */
export const Route = createFileRoute('/_shell/_signedIn/analytics/')({
  beforeLoad: () => {
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's redirect() is designed to be thrown
    throw redirect({ to: '/analytics/committees', replace: true })
  },
})
