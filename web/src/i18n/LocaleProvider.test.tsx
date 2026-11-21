import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { describe, expect, it } from 'vitest'
import { CatalogueGate } from './CatalogueGate'
import { LocaleProvider, useLocale } from './LocaleProvider'

const messages = defineMessages({
  navigation: { id: 'shell.navigation', defaultMessage: 'Main navigation' },
})

/** A page with something the reader chose (a count) and a way to change the language. */
function Page() {
  const intl = useIntl()
  const { setLocale } = useLocale()
  const [count, setCount] = useState(0)
  return (
    <>
      <p data-testid="page">
        {intl.formatMessage(messages.navigation)} · {count}
      </p>
      <button
        type="button"
        onClick={() => {
          setCount((c) => c + 1)
        }}
      >
        More
      </button>
      <button
        type="button"
        onClick={() => {
          setLocale('ar')
        }}
      >
        Arabic
      </button>
    </>
  )
}

describe('LocaleProvider', () => {
  it('keeps the page (and what the reader chose on it) while another language loads, then shows it translated', async () => {
    const user = userEvent.setup()
    render(
      <LocaleProvider locale="en">
        <CatalogueGate parts={[]}>
          <Page />
        </CatalogueGate>
      </LocaleProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'More' }))
    const page = screen.getByTestId('page')
    expect(page).toHaveTextContent('Main navigation · 1')

    await act(async () => {
      await user.click(screen.getByRole('button', { name: 'Arabic' }))
    })
    // Still the same page, in English until the Arabic catalogue is there: no loading state in between.
    expect(page).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(page).toHaveTextContent('التنقل الرئيسي · 1')
    })
    expect(page).toBeInTheDocument()
    expect(document.documentElement).toHaveAttribute('lang', 'ar')
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
  })
})
