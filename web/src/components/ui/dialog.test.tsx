import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import { Button } from './button'
import { AlertDialog, Dialog } from './dialog'

function DialogHarness() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button
        onClick={() => {
          setOpen(true)
        }}
      >
        open-dialog
      </Button>
      <Dialog open={open} onOpenChange={setOpen} title="Rename unit" description="Shown everywhere">
        <input aria-label="Name" />
      </Dialog>
    </>
  )
}

function ConfirmHarness({ onConfirm }: { onConfirm: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button
        onClick={() => {
          setOpen(true)
        }}
      >
        open-confirm
      </Button>
      <AlertDialog
        open={open}
        onOpenChange={setOpen}
        title="Deactivate?"
        description="They will be signed out."
        confirmLabel="Deactivate"
        cancelLabel="Cancel"
        destructive
        onConfirm={onConfirm}
      />
    </>
  )
}

describe('Dialog (ADR-0021)', () => {
  it('opens as a modal, moves focus in, closes on Escape and returns focus to the trigger', async () => {
    const user = userEvent.setup()
    const { container } = renderWithProviders(<DialogHarness />)
    const trigger = screen.getByRole('button', { name: 'open-dialog' })
    await user.click(trigger)

    const dialog = screen.getByRole('dialog', { name: 'Rename unit' })
    expect(dialog).toHaveAttribute('open')
    expect(dialog).toHaveAccessibleDescription('Shown everywhere')
    expect(dialog).toContainElement(document.activeElement as HTMLElement)
    expect(document.documentElement).toHaveClass('modal-open')
    await expectNoA11yViolations(container)

    await user.keyboard('{Escape}')
    expect(dialog).not.toHaveAttribute('open')
    expect(trigger).toHaveFocus()
    expect(document.documentElement).not.toHaveClass('modal-open')
  })

  it('the close button closes it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<DialogHarness />)
    await user.click(screen.getByRole('button', { name: 'open-dialog' }))
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('AlertDialog', () => {
  it('starts on Cancel so Enter never confirms a destructive action by accident', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    renderWithProviders(<ConfirmHarness onConfirm={onConfirm} />)
    await user.click(screen.getByRole('button', { name: 'open-confirm' }))
    expect(screen.getByRole('alertdialog', { name: 'Deactivate?' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('confirm runs the action and closes', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    renderWithProviders(<ConfirmHarness onConfirm={onConfirm} />)
    await user.click(screen.getByRole('button', { name: 'open-confirm' }))
    await user.click(screen.getByRole('button', { name: 'Deactivate' }))
    expect(onConfirm).toHaveBeenCalledOnce()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})
