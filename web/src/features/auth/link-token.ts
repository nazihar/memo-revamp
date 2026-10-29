/**
 * The one-time token of an emailed link, from the URL fragment (`/reset-password#<token>`): browsers never send the
 * fragment to servers, so the token stays out of request lines and access logs. It is removed from the address bar at
 * once, so it does not linger in history or screenshots. Repeated calls on the same page return the same token (React
 * StrictMode runs state initialisers twice).
 */
let taken: { path: string; token: string } | undefined

export function takeLinkToken(): string | undefined {
  const path = window.location.pathname
  const token = window.location.hash.replace(/^#/, '').trim()
  if (token) {
    taken = { path, token }
    window.history.replaceState(window.history.state, '', path + window.location.search)
  }
  return taken?.path === path ? taken.token : undefined
}
