import { useSyncExternalStore } from 'react'

/**
 * Recent search words (flow H1), kept in memory only: search terms can be sensitive (names, account numbers), so they
 * never go to Web Storage, and they go with the page. They belong to one person, so another sign-in in the same tab
 * does not see them.
 */
const MAX_RECENT = 5
const NONE: readonly string[] = []

let recent: { owner: string; terms: readonly string[] } = { owner: '', terms: NONE }
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function rememberSearch(owner: string, term: string) {
  const clean = term.trim()
  if (!owner || !clean) return
  const earlier = recent.owner === owner ? recent.terms.filter((t) => t !== clean) : []
  recent = { owner, terms: [clean, ...earlier].slice(0, MAX_RECENT) }
  listeners.forEach((l) => {
    l()
  })
}

export function forgetRecentSearches() {
  recent = { owner: '', terms: NONE }
  listeners.forEach((l) => {
    l()
  })
}

export function useRecentSearches(owner: string | undefined): readonly string[] {
  const current = useSyncExternalStore(subscribe, () => recent)
  return owner && current.owner === owner ? current.terms : NONE
}
