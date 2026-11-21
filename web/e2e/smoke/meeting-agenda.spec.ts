import type { Page } from '@playwright/test'
import { expect, test } from '../fixtures'
import { pdf } from '../legacy'
import { checkPageA11y, creditProposalToCommittee, dayFromNow } from '../meetings'
import { pageAs } from '../roles'

/**
 * The E03 secretary's journey (test plan T6–T8, flows N, O and Q): a credit proposal above the committee threshold
 * waits for the Management Credit Committee; its secretary accepts it, schedules a meeting, starts the agenda from the
 * committee's standard agenda and places the paper from the tray. With the keyboard only, an item is moved to another
 * section and made a sub-item, and a paper is uploaded. The committee's chair (Demo Director) agrees the agenda in the
 * app, the secretary publishes revision 1, replaces the paper and publishes revision 2 with a note. axe finds nothing
 * serious in English or Arabic.
 */
function menu(page: Page, title: string) {
  return page.getByRole('button', { name: `Actions for ${title}` })
}

/** Opens [title]'s row menu and chooses [command], with the keyboard. */
async function command(page: Page, title: string, label: string) {
  const trigger = menu(page, title)
  await trigger.focus()
  await trigger.press('Enter')
  await page.getByRole('menuitem', { name: label }).press('Enter')
}

/** What the builder said last to a screen reader (its polite live region). */
function said(page: Page) {
  return page.locator('main p[role="status"][aria-live="polite"]').first()
}

test.describe('as the company secretary', () => {
  test.use({ role: 'secretary' })

  test('schedules a meeting, builds its agenda with the keyboard and publishes two revisions', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(300_000)
    const stamp = String(Date.now())
    const subject = `E2E agenda ${stamp}`
    const meetingTitle = `E2E credit meeting ${stamp}`
    const noting = `E2E noting paper ${stamp}`
    await creditProposalToCommittee(browser, subject)

    // Accept the paper from the committee's Submissions tab.
    await page.goto('/committees')
    await page.getByRole('link', { name: 'Management Credit Committee' }).click()
    await page.getByRole('tab', { name: /^Submissions/ }).press('Enter')
    const triage = page.getByRole('button', { name: subject })
    await triage.focus()
    await triage.press('Enter')
    await page.getByRole('menuitem', { name: 'Accept…' }).press('Enter')
    const accept = page.getByRole('dialog', { name: 'Accept the paper' })
    await accept.getByRole('button', { name: 'Accept' }).press('Enter')
    await expect(page.getByText('Paper accepted.')).toBeVisible()

    // Schedule a meeting four weeks ahead, well before its paper deadline.
    await page.getByRole('tab', { name: 'Meetings' }).press('Enter')
    await page.getByRole('button', { name: 'Schedule a meeting' }).click()
    const sheet = page.getByRole('dialog', { name: 'New meeting of Management Credit Committee' })
    await sheet.getByRole('textbox', { name: /^Title/ }).fill(meetingTitle)
    await sheet.getByLabel(/^Date/).fill(dayFromNow(28))
    await sheet.getByLabel(/^From/).fill('14:00')
    await sheet.getByLabel(/^To/).fill('16:00')
    await sheet.getByRole('radio', { name: 'In person' }).check()
    await sheet.getByRole('textbox', { name: /^Where/ }).fill('Credit Committee Room')
    await app.checkA11y()
    await sheet.getByRole('button', { name: 'Schedule' }).click()
    await expect(page.getByText('Meeting scheduled. The members can see it now.')).toBeVisible()

    // The tab is in the address. At 400 % zoom (320 CSS pixels wide) the meetings table, whose actions column has a
    // visually hidden header, scrolls in its own box and the page does not scroll sideways (WCAG 1.4.10).
    await expect(page).toHaveURL(/[?&]tab=meetings\b/)
    await page.setViewportSize({ width: 320, height: 800 })
    await expect(page.getByRole('table').filter({ hasText: meetingTitle })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(
      0,
    )
    await page.setViewportSize({ width: 1280, height: 720 })
    await page.getByRole('link', { name: meetingTitle }).click()
    await page.getByRole('link', { name: 'Open the agenda builder' }).click()
    const builder = page.url()

    // Start from the committee's standard agenda and place the paper from the tray.
    await page.getByRole('button', { name: 'Start' }).click()
    await expect(menu(page, 'Welcome and apologies')).toBeVisible()
    const place = page
      .getByRole('listitem')
      .filter({ hasText: subject })
      .getByRole('button', { name: /^Add to agenda: / })
    await place.focus()
    await place.press('Enter')
    await expect(menu(page, subject)).toBeVisible()
    // Once the builder has said where it went, the rows have stopped moving.
    await expect(said(page)).toHaveText(/added at position 1 in For decision\.$/)
    await checkPageA11y(page)

    // The smoke stack has no document converter, so the memo's Word document has no PDF for members and would refuse
    // the publication (PAPER_NOT_READABLE): the secretary leaves it out of the pack, which keeps it with the memo.
    await command(page, subject, 'Papers…')
    const pack = page.getByRole('dialog', { name: `· ${subject}` })
    await pack.getByRole('button', { name: /^Actions for the paper Paper: / }).press('Enter')
    await page.getByRole('menuitem', { name: 'Remove from the pack' }).press('Enter')
    await page
      .getByRole('alertdialog', { name: /from the pack\?$/ })
      .getByRole('button', { name: 'Remove from the pack' })
      .press('Enter')
    await expect(pack.getByRole('button', { name: 'Put back in the pack' })).toBeVisible()
    // The paper's own menu went with it: focus stays in the sheet, on the line that heads the papers (none is left).
    await expect(pack.getByText('Papers in the order members see them.')).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(pack).toBeHidden()

    // An item of the builder's own, in For noting.
    await page.getByRole('button', { name: 'Add item' }).click()
    const adding = page.getByRole('dialog', { name: 'Add item' })
    await adding.getByRole('textbox', { name: /^Title on the agenda/ }).fill(noting)
    await adding.getByRole('combobox', { name: /^Section/ }).selectOption({ label: 'For noting' })
    await adding.getByRole('button', { name: 'Add' }).press('Enter')
    await expect(menu(page, noting)).toBeVisible()

    // With the keyboard: to For decision, then a sub-item of the credit paper above it.
    await command(page, noting, 'Move to section…')
    const moving = page.getByRole('dialog', { name: `Move ${noting} to another section` })
    await moving.getByRole('combobox', { name: /^Section/ }).selectOption({ label: 'For decision' })
    await moving.getByRole('radio', { name: 'At the end' }).check()
    await moving.getByRole('button', { name: 'Move' }).press('Enter')
    await expect(said(page)).toContainText(`, ${noting}, moved to position 2 in For decision.`)
    await expect(menu(page, noting)).toBeFocused()
    await command(page, noting, 'Make a sub-item of the item above')
    await expect(said(page)).toHaveText(`${noting} is now sub-item 1 of ${subject}.`)
    await expect(page.getByRole('list', { name: `Sub-items of ${subject}` })).toContainText(noting)

    // A paper on it, from the item's side sheet.
    await command(page, noting, 'Papers…')
    let side = page.getByRole('dialog', { name: `· ${noting}` })
    await side.getByLabel('Upload a paper').setInputFiles({
      name: 'noting.pdf',
      mimeType: 'application/pdf',
      buffer: pdf('Noting paper, version 1'),
    })
    await expect(side.getByRole('list', { name: 'Papers' })).toContainText('noting.pdf')
    await app.checkA11y()
    await page.keyboard.press('Escape')
    await expect(side).toBeHidden()

    // The chair agrees the agenda in the app.
    await expect(page.getByText('Waiting for the chair’s agreement')).toBeVisible()
    const chair = await pageAs(browser, 'director')
    await chair.goto(builder)
    await chair.getByRole('button', { name: 'Agree the agenda' }).click()
    await chair
      .getByRole('alertdialog', { name: 'Agree the agenda?' })
      .getByRole('button', { name: 'Agree the agenda' })
      .click()
    await expect(chair.getByText('You agreed the agenda.').first()).toBeAttached()
    await chair.context().close()

    // Revision 1.
    await page.reload()
    await page.getByRole('button', { name: 'Publish revision 1' }).click()
    let publishing = page.getByRole('dialog', { name: 'Publish revision 1 of the agenda' })
    await app.checkA11y()
    await publishing.getByRole('button', { name: 'Publish' }).press('Enter')
    await expect(page.getByText('Revision 1 of the agenda is published.').first()).toBeAttached()
    await expect(page.getByText('The members see the agenda as it is here.')).toBeVisible()

    // Replace the paper, and publish revision 2 with a note.
    await command(page, noting, 'Papers…')
    side = page.getByRole('dialog', { name: `· ${noting}` })
    await side.getByRole('button', { name: 'Actions for the paper noting.pdf' }).press('Enter')
    await page.getByRole('menuitem', { name: 'Replace with a new version…' }).press('Enter')
    await page.getByLabel('New version').setInputFiles({
      name: 'noting.pdf',
      mimeType: 'application/pdf',
      buffer: pdf('Noting paper, version 2'),
    })
    await expect(said(page)).toHaveText('Paper replaced.')
    await page.keyboard.press('Escape')
    await expect(page.getByText(/There are changes the members do not see yet/)).toBeVisible()
    await page.getByRole('button', { name: 'Publish revision 2' }).click()
    publishing = page.getByRole('dialog', { name: 'Publish revision 2 of the agenda' })
    await expect(publishing.getByRole('list')).toContainText('Paper replaced')
    await publishing.getByRole('textbox', { name: /Note to members/ }).fill('Updated noting paper')
    await publishing.getByRole('button', { name: 'Publish' }).press('Enter')
    await expect(page.getByText('Revision 2 of the agenda is published.').first()).toBeAttached()

    // At 400 % zoom (320 CSS pixels wide) the builder and its tray do not scroll sideways (WCAG 1.4.10).
    await page.setViewportSize({ width: 320, height: 800 })
    await expect(menu(page, noting)).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(
      0,
    )
    await page.setViewportSize({ width: 1280, height: 720 })

    // The builder in Arabic, right to left.
    await page.getByRole('button', { name: /Change language/ }).click()
    await page.getByRole('menuitemradio', { name: 'العربية' }).click()
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
    await checkPageA11y(page)
    await page.getByRole('button', { name: /تغيير اللغة|Change language/ }).click()
    await page.getByRole('menuitemradio', { name: 'English' }).click()
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr')
  })
})
