import { expect, test } from '../fixtures'

/**
 * E01 app frame and page templates on the patterns gallery (development and e2e builds only). Every visit runs axe and
 * the CSP-violation fixture, so opening each modal here proves ADR-0021 (no inline styles, no aria-hidden on the root).
 */
test('gallery renders every template accessibly in light and dark', async ({ app, page }) => {
  await app.visit('/patterns')
  await expect(page.getByRole('heading', { level: 1, name: 'Patterns' })).toBeVisible()
  await expect(page.getByRole('table', { name: 'Users' })).toBeVisible()
  await expect(page.getByRole('tree', { name: 'Units' })).toBeVisible()

  await page.getByRole('button', { name: /Change theme/ }).click()
  await page.getByRole('menuitemradio', { name: 'Dark' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await app.checkA11y()
})

test('list template: sorting, selection and cursor pagination', async ({ app, page }) => {
  await app.visit('/patterns')
  const table = page.getByRole('table', { name: 'Users' })
  const nameHeader = table.getByRole('columnheader', { name: /Name/ })
  await nameHeader.getByRole('button', { name: 'Sort by Name' }).click()
  await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending')

  const pagination = page.getByRole('navigation', { name: 'Pagination' })
  await expect(pagination.getByRole('button', { name: 'Previous' })).toBeDisabled()
  await pagination.getByRole('button', { name: 'Next' }).click()
  await expect(pagination).toContainText('Rows 9–16')
  await expect(pagination.getByRole('button', { name: 'Previous' })).toBeEnabled()

  await table.getByRole('checkbox', { name: 'Select all rows' }).check()
  await expect(table.getByRole('checkbox', { name: /^Select row / }).first()).toBeChecked()
})

test('modal dialog and confirmation: focus, Escape and no CSP violations', async ({ app, page }) => {
  await app.visit('/patterns')
  const opener = page.getByRole('button', { name: 'Open dialog' })
  await opener.click()
  const dialog = page.getByRole('dialog', { name: 'Rename unit' })
  await expect(dialog).toBeVisible()
  await app.checkA11y()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(opener).toBeFocused()

  await page.getByRole('button', { name: 'Deactivate user' }).click()
  const confirm = page.getByRole('alertdialog', { name: 'Deactivate this user?' })
  await expect(confirm).toBeVisible()
  await expect(confirm.getByRole('button', { name: 'Cancel' })).toBeFocused()
  await app.checkA11y()
  await confirm.getByRole('button', { name: 'Deactivate' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Changes saved' })).toBeVisible()
})

test('form template: server field error focuses the field; unsaved changes ask before leaving', async ({
  app,
  page,
}) => {
  await app.visit('/patterns')
  const code = page.getByRole('textbox', { name: 'Code' })
  await code.fill('TAKEN')
  await expect(page.getByText('You have unsaved changes.')).toBeVisible()
  await page.getByRole('button', { name: 'Save changes' }).click()
  await expect(page.getByText('This code is already used by another unit.')).toBeVisible()
  await expect(code).toBeFocused()
  await expect(code).toHaveAttribute('aria-invalid', 'true')

  await page.getByRole('link', { name: 'Status' }).first().click()
  const guard = page.getByRole('alertdialog', { name: 'Leave without saving?' })
  await expect(guard).toBeVisible()
  await guard.getByRole('button', { name: 'Stay on this page' }).click()
  await expect(page).toHaveURL(/\/patterns/)
})

test('tree template is keyboard operable', async ({ app, page }) => {
  await app.visit('/patterns')
  const tree = page.getByRole('tree', { name: 'Units' })
  await tree.getByRole('treeitem', { name: 'Credit Risk' }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(tree.getByRole('treeitem', { name: 'Credit Risk' })).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('ArrowDown')
  await expect(tree.getByRole('treeitem', { name: 'Retail Credit' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByText('Selected: Retail Credit')).toBeVisible()
})

test.describe('small screens', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('navigation opens in a sheet from the menu button', async ({ app, page }) => {
    await app.visit('/patterns')
    await page.getByRole('button', { name: 'Open menu' }).click()
    const sheet = page.getByRole('dialog', { name: 'Main navigation' })
    await expect(sheet).toBeVisible()
    await app.checkA11y()
    await sheet.getByRole('link', { name: 'Status' }).click()
    await expect(sheet).toBeHidden()
    await expect(page).toHaveURL(/\/status$/)
  })
})
