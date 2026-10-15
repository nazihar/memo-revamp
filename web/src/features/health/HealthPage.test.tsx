import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { client } from '@/api/generated/client.gen'
import type { Health } from '@/api/generated/types.gen'
import { API_ORIGIN, healthHandler, server } from '@/test/msw'
import { HealthPage } from './HealthPage'

const up: Health = {
  status: 'UP',
  checkedAt: '2026-10-14T10:00:00Z',
  components: [
    { name: 'database', status: 'UP' },
    { name: 'objectStorage', status: 'UP' },
    { name: 'documentServer', status: 'DISABLED' },
    { name: 'malwareScanner', status: 'DOWN' },
    { name: 'mail', status: 'UP' },
  ],
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <HealthPage />
    </QueryClientProvider>,
  )
}

describe('HealthPage', () => {
  beforeEach(() => client.setConfig({ baseUrl: API_ORIGIN }))

  it('shows a loading state, then the overall status and every component as text', async () => {
    server.use(healthHandler(up))
    renderPage()

    expect(screen.getByRole('status')).toHaveTextContent('Checking platform status')
    expect(await screen.findByTestId('overall-status')).toHaveTextContent('Up')
    expect(screen.getByRole('heading', { level: 1, name: 'Platform status' })).toBeInTheDocument()
    expect(screen.getByText('Database')).toBeInTheDocument()
    expect(screen.getByText('Not configured')).toBeInTheDocument()
    expect(screen.getByText('Down')).toBeInTheDocument()
  })

  it('renders the DOWN body that comes with a 503', async () => {
    server.use(healthHandler({ ...up, status: 'DOWN', components: [{ name: 'database', status: 'DOWN' }] }, 503))
    renderPage()

    const overall = await screen.findByTestId('overall-status')
    expect(overall).toHaveTextContent('Down')
    expect(overall).toHaveAttribute('data-status', 'DOWN')
  })

  it('shows an alert when the backend is unreachable', async () => {
    server.use(http.get(`${API_ORIGIN}/api/v1/health`, () => HttpResponse.error()))
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('The platform could not be reached.')
  })
})
