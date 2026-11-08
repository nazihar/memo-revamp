import { createFileRoute, Outlet } from '@tanstack/react-router'
import { PlatformFrame } from '@/features/platform/PlatformFrame'

/** The operator console (ADR-0031), served on its own host; its pages never touch the tenant API. */
export const Route = createFileRoute('/platform')({
  component: () => (
    <PlatformFrame>
      <Outlet />
    </PlatformFrame>
  ),
})
