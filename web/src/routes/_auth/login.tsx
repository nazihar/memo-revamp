import { createFileRoute } from '@tanstack/react-router'
import { LoginPage } from '@/features/auth/LoginPage'

export const Route = createFileRoute('/_auth/login')({
  /** `next`: where to go after signing in (same-origin paths only; see pathForState). */
  validateSearch: (search: Record<string, unknown>): { next?: string } =>
    typeof search.next === 'string' ? { next: search.next } : {},
  component: LoginRoute,
})

function LoginRoute() {
  const { next } = Route.useSearch()
  return <LoginPage {...(next ? { next } : {})} />
}
