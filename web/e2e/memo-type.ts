import type { Page } from '@playwright/test'
import { expect } from './fixtures'

/**
 * Chooses the memo type on `/memos/new` (Phase 3 E01 T7): a few types are radio choices, many a list labelled
 * "Memo type"; how many there are depends on what the tenant has built.
 */
export async function chooseMemoType(page: Page, name: string) {
  const list = page.getByRole('combobox', { name: 'Memo type' })
  const choices = page.getByRole('group', { name: /^Memo type/ })
  await expect(list.or(choices)).toBeVisible()
  if (await list.isVisible()) {
    await list.selectOption({ label: name })
  } else {
    await choices
      .locator('label')
      .filter({ has: page.getByText(name, { exact: true }) })
      .getByRole('radio')
      .check()
  }
}
