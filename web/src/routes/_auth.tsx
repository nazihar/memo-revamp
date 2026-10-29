import { createFileRoute, Outlet } from '@tanstack/react-router'
import { AuthLayout } from '@/features/auth/AuthLayout'

/** The sign-in card frame: sign-in, MFA enrolment, password reset, invitation. */
export const Route = createFileRoute('/_auth')({
  component: AuthFrame,
})

function AuthFrame() {
  return (
    <AuthLayout>
      <Outlet />
    </AuthLayout>
  )
}
