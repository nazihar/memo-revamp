import { createFileRoute } from '@tanstack/react-router'
import { TenantPage } from '@/features/platform/TenantPage'

export const Route = createFileRoute('/platform/_operator/tenants/$tenantId')({
  component: TenantRoute,
})

function TenantRoute() {
  const { tenantId } = Route.useParams()
  return <TenantPage tenantId={tenantId} />
}
