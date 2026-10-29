import { afterEach, describe, expect, it, vi } from 'vitest'
import { CSRF_HEADER, csrfFetch } from './http'

function setCookie(value: string) {
  document.cookie = `XSRF-TOKEN=${value}; path=/`
}

describe('csrfFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  it('sends a mutation once more when another response replaced the cookie while it was in flight', async () => {
    setCookie('first')
    const sent: (string | null)[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((request: Request) => {
        sent.push(request.headers.get(CSRF_HEADER))
        if (sent.length === 1) {
          setCookie('second') // a concurrent cookieless response set a new token
          return Promise.resolve(new Response(null, { status: 403 }))
        }
        return Promise.resolve(new Response('{}', { status: 200 }))
      }),
    )

    const response = await csrfFetch('http://localhost/api/v1/auth/invitations/lookup', { method: 'POST', body: '{}' })

    expect(response.status).toBe(200)
    expect(sent).toEqual(['first', 'second'])
  })

  it('does not repeat a refused mutation when the token did not change', async () => {
    setCookie('only')
    const fetchMock = vi.fn(() => Promise.resolve(new Response(null, { status: 403 })))
    vi.stubGlobal('fetch', fetchMock)

    const response = await csrfFetch('http://localhost/api/v1/admin/users', { method: 'POST', body: '{}' })

    expect(response.status).toBe(403)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('sends reads without the header', async () => {
    setCookie('token')
    let header: string | null = 'unset'
    vi.stubGlobal(
      'fetch',
      vi.fn((request: Request) => {
        header = request.headers.get(CSRF_HEADER)
        return Promise.resolve(new Response('{}', { status: 200 }))
      }),
    )

    await csrfFetch('http://localhost/api/v1/me')

    expect(header).toBeNull()
  })
})
