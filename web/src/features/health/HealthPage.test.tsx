import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { API_ORIGIN, healthHandler, healthyResponse, server } from '@/test/msw'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import { HealthPage } from './HealthPage'

describe('HealthPage', () => {
  it('shows a loading state, then the overall status and every component as text', async () => {
    const { container } = renderWithProviders(<HealthPage />)

    expect(screen.getByRole('status')).toHaveTextContent('Checking platform status')
    expect(await screen.findByText('Overall')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Platform status' })).toBeInTheDocument()
    expect(screen.getByText('Database')).toBeInTheDocument()
    expect(screen.getByText('Not configured')).toBeInTheDocument()
    expect(screen.getByText('Down')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('renders the DOWN body that comes with a 503', async () => {
    server.use(
      healthHandler({ ...healthyResponse, status: 'DOWN', components: [{ name: 'database', status: 'DOWN' }] }, 503),
    )
    const { container } = renderWithProviders(<HealthPage />)

    await screen.findByText('Overall')
    const overall = container.querySelector('p [data-status]')
    expect(overall).toHaveAttribute('data-status', 'DOWN')
    expect(overall).toHaveTextContent('Down')
  })

  it('shows an alert when the backend is unreachable', async () => {
    server.use(http.get(`${API_ORIGIN}/api/v1/health`, () => HttpResponse.error()))
    renderWithProviders(<HealthPage />)

    expect(await screen.findByRole('alert')).toHaveTextContent('The platform could not be reached.')
  })

  it('is fully translated in Arabic', async () => {
    renderWithProviders(<HealthPage />, { locale: 'ar' })
    expect(await screen.findByRole('heading', { level: 1, name: 'حالة المنصة' })).toBeInTheDocument()
    expect(await screen.findByText('قاعدة البيانات')).toBeInTheDocument()
  })

  it('shows unknown components from a newer backend by their raw name', async () => {
    server.use(healthHandler({ ...healthyResponse, components: [{ name: 'futureThing', status: 'UP' }] }))
    renderWithProviders(<HealthPage />)
    expect(await screen.findByText('futureThing')).toBeInTheDocument()
  })
})
