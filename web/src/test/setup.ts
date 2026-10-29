import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, beforeEach, vi } from 'vitest'
import { client } from '@/api/generated/client.gen'
import { configureHttp } from '@/api/http'
import { installDialogPolyfill } from './dialog-polyfill'
import { API_ORIGIN, server } from './msw'

installDialogPolyfill()
configureHttp()

/** jsdom has no matchMedia; tests can flip `prefersDark` to simulate the OS theme. */
export const media = { prefersDark: false }

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' })
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('dark') ? media.prefersDark : false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }))
})

beforeEach(() => {
  client.setConfig({ baseUrl: API_ORIGIN })
  localStorage.clear()
  media.prefersDark = false
  document.documentElement.className = ''
  document.documentElement.lang = 'en'
  document.documentElement.dir = 'ltr'
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

afterEach(() => {
  server.resetHandlers()
  cleanup()
})

afterAll(() => {
  server.close()
})
