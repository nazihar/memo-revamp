import './styles/index.css'
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRouter, RouterProvider } from '@tanstack/react-router'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { getSessionQueryKey } from './api/generated/@tanstack/react-query.gen'
import { configureHttp, csrfToken, isProblem, PROBLEM } from './api/http'
import { LocaleProvider } from './i18n/LocaleProvider'
import { routeTree } from './routeTree.gen'
import { ThemeProvider } from './theme/ThemeProvider'

configureHttp()

/**
 * A 401 outside the sign-in steps means the session ended (idle or absolute timeout, revoked elsewhere): drop the cached
 * session and go to sign-in, back to this page afterwards. Wrong credentials and step-up prompts are handled in place.
 */
function onApiError(error: unknown) {
  if (!isProblem(error) || error.status !== 401) return
  if (error.type === PROBLEM.stepUpRequired || error.type === PROBLEM.invalidCredentials) return
  void queryClient.invalidateQueries({ queryKey: getSessionQueryKey() })
  void router.navigate({ to: '/login', search: { next: window.location.pathname } })
}

const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: onApiError }),
  mutationCache: new MutationCache({ onError: onApiError }),
  defaultOptions: {
    // Client errors (4xx) will not change on a retry; network and server errors may.
    queries: {
      staleTime: 5_000,
      retry: (count, error) => count < 1 && !(isProblem(error) && (error.status ?? 500) < 500),
    },
  },
})

const router = createRouter({ routeTree, context: { queryClient }, defaultPreload: 'intent' })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

const root = document.getElementById('root')
if (!root) throw new Error('#root element missing from index.html')

const render = () => {
  createRoot(root).render(
    <StrictMode>
      <ThemeProvider>
        <LocaleProvider>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </LocaleProvider>
      </ThemeProvider>
    </StrictMode>,
  )
}

// The server gives every request without the CSRF cookie a new token, so on a first visit the app's first requests
// would race to set it (a mutation could go out with a token another response had just replaced). Fetch it once first;
// render regardless if the backend cannot be reached.
void csrfToken()
  .catch(() => undefined)
  .finally(render)
