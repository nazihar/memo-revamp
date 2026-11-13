import { createFileRoute, Outlet } from '@tanstack/react-router'
import { PlatformFrame } from '@/features/platform/PlatformFrame'
import { CatalogueGate } from '@/i18n/CatalogueGate'

/** The operator console (ADR-0031), served on its own host; its pages never touch the tenant API. */
export const Route = createFileRoute('/platform')({
  component: () => (
    <CatalogueGate parts={['platform']}>
      <PlatformFrame>
        <Outlet />
      </PlatformFrame>
    </CatalogueGate>
  ),
})
