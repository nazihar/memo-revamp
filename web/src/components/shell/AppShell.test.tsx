import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { media } from '@/test/setup'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

describe('AppShell', () => {
  it('has banner, navigation and main landmarks and is axe-clean', async () => {
    const { container } = renderRoute('/')
    expect(await screen.findByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    await screen.findByText('Overall')
    await expectNoA11yViolations(container)
  })

  it('the menu button opens the navigation in a modal sheet and a link closes it', async () => {
    const user = userEvent.setup()
    renderRoute('/')
    await user.click(await screen.findByRole('button', { name: 'Open menu' }))
    const sheet = screen.getByRole('dialog', { name: 'Main navigation' })
    expect(sheet).toHaveAttribute('open')
    await user.click(within(sheet).getByRole('link', { name: 'Status' }))
    expect(sheet).not.toHaveAttribute('open')
  })

  it('marks the current page in the navigation and the breadcrumb', async () => {
    renderRoute('/')
    await screen.findByRole('banner')
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'Status' })).toHaveAttribute('aria-current', 'page')
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByText('Status')).toHaveAttribute('aria-current', 'page')
  })

  it('skip link is the first tab stop and targets main', async () => {
    renderRoute('/')
    await screen.findByRole('banner')
    await userEvent.tab()
    const skip = screen.getByRole('link', { name: 'Skip to main content' })
    expect(skip).toHaveFocus()
    expect(skip).toHaveAttribute('href', '#main')
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main')
  })

  it('switching to Arabic sets lang and dir on <html>, translates the shell and persists the choice', async () => {
    const user = userEvent.setup()
    renderRoute('/')
    await user.click(await screen.findByRole('button', { name: /Change language/ }))
    await user.click(await screen.findByRole('menuitemradio', { name: 'العربية' }))

    await waitFor(() => {
      expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    })
    expect(document.documentElement).toHaveAttribute('lang', 'ar')
    expect(await screen.findByRole('navigation', { name: 'التنقل الرئيسي' })).toBeInTheDocument()
    expect(localStorage.getItem('memo.locale')).toBe('ar')
  })

  it('restores a saved locale on start', async () => {
    localStorage.setItem('memo.locale', 'ar')
    renderRoute('/')
    expect(await screen.findByRole('heading', { level: 1, name: 'حالة المنصة' })).toBeInTheDocument()
  })

  it('theme menu applies dark mode and persists it', async () => {
    const user = userEvent.setup()
    renderRoute('/')
    await user.click(await screen.findByRole('button', { name: /Change theme/ }))
    await user.click(await screen.findByRole('menuitemradio', { name: 'Dark' }))
    expect(document.documentElement).toHaveClass('dark')
    expect(localStorage.getItem('memo.theme')).toBe('dark')
  })

  it('system theme follows the operating system preference', async () => {
    media.prefersDark = true
    renderRoute('/')
    await screen.findByRole('banner')
    expect(document.documentElement).toHaveClass('dark')
  })

  it('stores nothing but preference keys in Web Storage', async () => {
    const user = userEvent.setup()
    renderRoute('/')
    await user.click(await screen.findByRole('button', { name: /Change theme/ }))
    await user.click(await screen.findByRole('menuitemradio', { name: 'Light' }))
    expect(Object.keys(localStorage).filter((k) => !['memo.theme', 'memo.locale'].includes(k))).toEqual([])
    expect(sessionStorage.length).toBe(0)
  })
})
