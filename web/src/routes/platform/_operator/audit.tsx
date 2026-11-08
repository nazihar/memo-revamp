import { createFileRoute } from '@tanstack/react-router'
import { PlatformAuditPage } from '@/features/platform/PlatformAuditPage'

export const Route = createFileRoute('/platform/_operator/audit')({
  component: PlatformAuditPage,
})
