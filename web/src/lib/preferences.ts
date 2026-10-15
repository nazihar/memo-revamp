/**
 * Per-browser UI preferences. Only these keys may ever be written to Web Storage — no tokens, no personal data
 * (AGENTS.md §2; verified by the e2e storage-hygiene check). Access is wrapped: storage can be unavailable.
 */
export const PREFERENCE_KEYS = { theme: 'memo.theme', locale: 'memo.locale' } as const

type PreferenceKey = (typeof PREFERENCE_KEYS)[keyof typeof PREFERENCE_KEYS]

export function readPreference(key: PreferenceKey): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

export function writePreference(key: PreferenceKey, value: string): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // Storage unavailable: the preference lasts for this page only.
  }
}
