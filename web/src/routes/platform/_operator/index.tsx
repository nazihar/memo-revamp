import { createFileRoute } from '@tanstack/react-router'
import { TenantsPage } from '@/features/platform/TenantsPage'

export const Route = createFileRoute('/platform/_operator/')({
  component: TenantsPage,
})
