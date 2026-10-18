import { describe, expect, it, vi } from 'vitest'
import { applyFieldErrors, isProblemDetails } from './problem-details'

describe('applyFieldErrors', () => {
  it('sets errors on known fields, focuses the first one and returns the rest', () => {
    document.body.innerHTML = '<form><input name="name"><input name="code"></form>'
    const form = document.querySelector('form')
    const setError = vi.fn()
    const unmatched = applyFieldErrors(
      {
        title: 'Validation failed',
        errors: [
          { field: 'code', message: 'Already used' },
          { field: 'name', message: 'Too long' },
          { field: 'unknown', message: 'Not allowed' },
        ],
      },
      ['name', 'code'],
      setError,
      form,
    )
    expect(setError).toHaveBeenCalledWith('code', 'Already used')
    expect(setError).toHaveBeenCalledWith('name', 'Too long')
    expect(unmatched).toEqual(['Not allowed'])
    expect(document.activeElement).toBe(document.querySelector('[name="code"]'))
  })

  it('recognises Problem Details payloads', () => {
    expect(isProblemDetails({ title: 'Forbidden', status: 403 })).toBe(true)
    expect(isProblemDetails('oops')).toBe(false)
    expect(isProblemDetails(null)).toBe(false)
  })
})
