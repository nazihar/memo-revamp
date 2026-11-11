import { describe, expect, it } from 'vitest'
import { newIdempotencyKey } from './idempotency'

describe('newIdempotencyKey', () => {
  it('makes distinct UUID v4 strings', () => {
    const keys = new Set(Array.from({ length: 100 }, newIdempotencyKey))
    expect(keys.size).toBe(100)
    for (const key of keys) expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })
})
