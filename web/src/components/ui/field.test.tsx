import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { Field } from './field'
import { Input } from './form-controls'

describe('Field', () => {
  it('labels the control and announces hint and error', () => {
    renderWithProviders(
      <Field label="Code" hint="2–10 letters" error="Already used" required>
        {(p) => <Input {...p} name="code" />}
      </Field>,
    )
    const input = screen.getByRole('textbox', { name: 'Code' })
    expect(input).toHaveAccessibleDescription('2–10 letters Already used')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toBeRequired()
  })

  it('an optional field without an error is not invalid', () => {
    renderWithProviders(
      <Field label="Description" optionalLabel="(optional)">
        {(p) => <Input {...p} />}
      </Field>,
    )
    const input = screen.getByRole('textbox', { name: 'Description (optional)' })
    expect(input).not.toHaveAttribute('aria-invalid')
    expect(input).not.toBeRequired()
  })
})
