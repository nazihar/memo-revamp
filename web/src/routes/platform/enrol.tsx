import { createFileRoute } from '@tanstack/react-router'
import { PlatformCard } from '@/features/platform/PlatformFrame'
import { PlatformEnrolPage } from '@/features/platform/PlatformEnrolPage'

/** The enrolment link printed by the `operator-invite` command; the token is in the fragment. */
export const Route = createFileRoute('/platform/enrol')({
  component: () => (
    <PlatformCard>
      <PlatformEnrolPage />
    </PlatformCard>
  ),
})
