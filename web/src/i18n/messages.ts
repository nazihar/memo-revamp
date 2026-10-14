// Minimal message catalog for E01. E03 replaces it with the ICU message pipeline chosen by ADR (AGENTS.md §3.6);
// components already read every string through `t()` so nothing is hard-coded in JSX.
const en = {
  'app.name': 'Memo & Meeting Management',
  'health.title': 'Platform status',
  'health.loading': 'Checking platform status…',
  'health.unreachable': 'The platform could not be reached.',
  'health.checkedAt': 'Last checked',
  'health.components': 'Components',
  'health.status.UP': 'Up',
  'health.status.DEGRADED': 'Degraded',
  'health.status.DOWN': 'Down',
  'health.status.DISABLED': 'Not configured',
  'health.component.database': 'Database',
  'health.component.objectStorage': 'Object storage',
  'health.component.documentServer': 'Document server',
  'health.component.malwareScanner': 'Malware scanner',
  'health.component.mail': 'Mail',
} as const

export type MessageKey = keyof typeof en

export function t(key: MessageKey): string {
  return en[key]
}

/** For keys built from API values (e.g. component names); falls back to the raw value rather than crashing. */
export function tDynamic(key: string, fallback: string): string {
  return key in en ? en[key as MessageKey] : fallback
}
