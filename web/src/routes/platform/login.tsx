import { createFileRoute } from '@tanstack/react-router'
import { PlatformCard } from '@/features/platform/PlatformFrame'
import { PlatformLoginPage } from '@/features/platform/PlatformLoginPage'

export const Route = createFileRoute('/platform/login')({
  component: () => (
    <PlatformCard>
      <PlatformLoginPage />
    </PlatformCard>
  ),
})
