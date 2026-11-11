/**
 * A fresh `Idempotency-Key` (AGENTS.md §4): a random UUID v4. Built from `crypto.getRandomValues`, which every browser
 * offers, rather than `crypto.randomUUID`, which exists only in secure contexts: an installation reached over plain
 * HTTP on an internal host name would otherwise fail on every create and action.
 */
export function newIdempotencyKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
