import { createFileRoute } from '@tanstack/react-router'
import { NewTenantPage } from '@/features/platform/NewTenantPage'

export const Route = createFileRoute('/platform/_operator/tenants/new')({
  component: NewTenantPage,
})
