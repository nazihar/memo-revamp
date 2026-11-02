import type { Page } from '@playwright/test'
import { expect, test } from '../fixtures'

/**
 * Org directory journeys (E04 test plan T10) on the real stack with the Demo Bank: the org administrator builds a unit
 * and a position and places a new joiner; a branch administrator can change their region only; a user reads.
 */

async function openUnit(page: Page, name: string, code: string) {
  await page.getByRole('searchbox', { name: 'Find a unit' }).fill(name)
  await page.getByRole('button', { name: `${name} (${code})` }).click()
  await expect(page.getByRole('heading', { level: 2, name })).toBeVisible()
  await page.getByRole('searchbox', { name: 'Find a unit' }).fill('')
}

test.describe('as the org administrator', () => {
  test.use({ role: 'orgAdmin' })

  test('adds a unit and a position, places a new joiner through the picker, and sees the history', async ({
    app,
    page,
  }) => {
    const run = String(Date.now()).slice(-6)
    const unitName = `SME Credit ${run}`
    const title = `SME Analyst ${run}`

    await app.visit('/org')
    await expect(page.getByRole('tree', { name: 'Units' })).toBeVisible()
    await openUnit(page, 'Credit Division', 'CRD')
    await page.getByRole('button', { name: 'Add sub-unit' }).click()
    const unitDialog = page.getByRole('dialog', { name: 'New unit under Credit Division' })
    await unitDialog.getByLabel('Name').fill(unitName)
    await unitDialog.getByLabel('Code').fill(`SME-${run}`)
    await unitDialog.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByRole('heading', { level: 2, name: unitName })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Unit path' })).toContainText('Credit Division')

    await page.getByRole('button', { name: 'Add position' }).click()
    const positionDialog = page.getByRole('dialog', { name: `New position in ${unitName}` })
    await positionDialog.getByLabel(/Title/).fill(title)
    await positionDialog.getByRole('button', { name: 'Save' }).click()
    await page.getByRole('link', { name: title }).click()
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
    await app.checkA11y()

    await page.getByRole('button', { name: 'Assign person' }).click()
    const assign = page.getByRole('dialog', { name: `Assign a person to ${title}` })
    await assign.getByRole('combobox').fill('Sohel')
    await assign.getByRole('option', { name: /Sohel Rana/ }).click()
    await app.checkA11y()
    await assign.getByRole('button', { name: 'Assign person' }).click()
    const holders = page.getByRole('table', { name: 'Who held this position' })
    await expect(holders.getByRole('row', { name: /Sohel Rana/ })).toContainText('Current')

    // Recorded by mistake: cancelling keeps the record (and frees the new joiner for the next run).
    await holders
      .getByRole('row', { name: /Sohel Rana/ })
      .getByRole('button', { name: 'Cancel record' })
      .click()
    await page
      .getByRole('alertdialog', { name: 'Cancel this record?' })
      .getByRole('button', { name: 'Cancel record' })
      .click()
    await expect(holders.getByRole('row', { name: /Sohel Rana/ })).toContainText('Cancelled')

    await page.getByText('Change history').click()
    await expect(page.getByRole('table', { name: 'Changes, newest first' })).toContainText('Created')
  })
})

test.describe('as a branch administrator', () => {
  test.use({ role: 'branchAdmin' })

  test('can change units in their region and only read the rest', async ({ app, page }) => {
    await app.visit('/org')
    await openUnit(page, 'Gulshan Branch', 'BR-GUL')
    await expect(page.getByRole('button', { name: 'Add position' })).toBeVisible()
    await openUnit(page, 'Treasury', 'FIN-TRS')
    await expect(page.getByRole('table', { name: 'Positions of the unit' })).toContainText('Head of Treasury')
    await expect(page.getByRole('button', { name: 'Add position' })).toHaveCount(0)
  })
})

test.describe('as a user', () => {
  test.use({ role: 'user' })

  test('reads the directory and sees no administration', async ({ app, page }) => {
    await app.visit('/org')
    await openUnit(page, 'Retail Credit', 'CRD-RTL')
    await expect(page.getByRole('table', { name: 'Positions of the unit' })).toContainText('Credit Officer')
    await expect(page.getByRole('button', { name: 'Add position' })).toHaveCount(0)
    await expect(page.getByRole('link', { name: 'Roles' })).toHaveCount(0)
  })
})
