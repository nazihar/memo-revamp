import type { Locator, Page } from '@playwright/test'
import { expect, test } from '../fixtures'
import { checkPageA11y, committeeId, dayFromNow, MeetingApi } from '../meetings'
import { pageAs } from '../roles'

/**
 * The E05 replies journey (test plan T7 and T10, flows V and W), on a hybrid Board Risk Committee meeting the company
 * secretary schedules through the API:
 * - Demo Director, a member, answers "Yes, online" with the keyboard, then changes to apologies with their alternate
 *   (Demo Head of Credit, the seat's alternate in the demo) attending in their place, with a note;
 * - Demo Head of Credit sees that they attend in the director's place;
 * - the secretary records Demo Board Member's "Yes, in person" given by phone, and the Invitations and replies tab shows
 *   both answers with how each came.
 * Every target of "Will you attend?" is at least 44 px, and axe finds nothing serious in English or Arabic.
 */
interface Meeting {
  id: string
  version: number
}

/** Buttons and links of [region] smaller than 44 by 44 CSS pixels (flow V). */
async function smallTargets(region: Locator): Promise<string[]> {
  const small: string[] = []
  for (const target of await region.locator('button, a').all()) {
    if (!(await target.isVisible())) continue
    const box = await target.boundingBox()
    if (box && (box.height < 44 || box.width < 44)) {
      small.push(`${(await target.innerText()).trim()}: ${String(box.width)}×${String(box.height)}`)
    }
  }
  return small
}

async function toArabic(page: Page) {
  await page.getByRole('button', { name: /Change language/ }).click()
  await page.getByRole('menuitemradio', { name: 'العربية' }).click()
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
}

async function toEnglish(page: Page) {
  await page.getByRole('button', { name: /تغيير اللغة|Change language/ }).click()
  await page.getByRole('menuitemradio', { name: 'English' }).click()
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr')
}

/** The row of the invitations table whose row header is [name]. */
function row(table: Locator, name: string): Locator {
  return table.getByRole('row').filter({ has: table.page().getByRole('rowheader', { name, exact: true }) })
}

test.describe('replies to an invitation', () => {
  test.use({ role: 'director' })

  test('a member answers, then sends apologies with their alternate; the secretary records an answer by phone', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(240_000)
    const title = `E2E replies ${String(Date.now())}`

    // The secretary schedules a hybrid meeting: the invitations go out and the members can answer.
    const secretary = await pageAs(browser, 'secretary')
    const api = new MeetingApi(secretary)
    const brc = await committeeId(api, 'BRC')
    const day = dayFromNow(28)
    const created = await api.send<Meeting>(`/forums/${brc}/meetings`, {
      kind: 'ORDINARY',
      title,
      startsAt: `${day}T10:00:00`,
      endsAt: `${day}T12:00:00`,
      timeZone: 'Asia/Dhaka',
      mode: 'HYBRID',
      location: 'Board Room, Level 12',
      joinUrl: 'https://meet.example.com/brc',
    })
    await api.send(`/meetings/${created.id}/schedule`, {}, { version: created.version })

    // The director answers "Yes, online" with the keyboard; focus moves to the saved answer.
    await app.visit(`/meetings/${created.id}`)
    const card = page.getByRole('region', { name: 'Will you attend?' })
    const answers = card.getByRole('group', { name: 'Will you attend?' })
    await expect(answers.getByRole('button', { name: 'Yes, in person' })).toBeVisible()
    await expect(card.getByRole('link', { name: 'Add to my calendar' })).toBeVisible()
    expect(await smallTargets(card)).toEqual([])
    const online = answers.getByRole('button', { name: 'Yes, online' })
    await online.focus()
    await online.press('Enter')
    await expect(card.getByText(/^You said: Yes, online/)).toBeFocused()
    await expect(card.getByRole('status')).toHaveText('Your answer is saved.')

    // Changes to apologies: the seat's alternate attends in their place by default.
    await card.getByRole('button', { name: 'Change my answer' }).press('Enter')
    await card.getByRole('button', { name: 'No, I send my apologies' }).press('Enter')
    const apologies = page.getByRole('dialog', { name: 'No, I send my apologies' })
    await expect(apologies).toContainText('Your alternate is Demo Head of Credit.')
    await expect(apologies.getByRole('radio', { name: 'Demo Head of Credit will attend in my place' })).toBeChecked()
    await apologies.getByRole('textbox', { name: /A note for the secretary/ }).fill('Travelling that week.')
    await checkPageA11y(page)
    await apologies.getByRole('button', { name: 'Send my apologies' }).press('Enter')
    await expect(apologies).toBeHidden()
    await expect(card.getByRole('status')).toHaveText(
      'Your apologies are recorded. Demo Head of Credit has been asked to attend in your place and will receive the invitation.',
    )
    await expect(card).toContainText('Demo Head of Credit will attend in your place.')
    expect(await smallTargets(card)).toEqual([])
    await page.setViewportSize({ width: 320, height: 800 })
    await expect(card).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(
      0,
    )
    await page.setViewportSize({ width: 1280, height: 720 })
    await checkPageA11y(page)

    // In Arabic, right to left, still without serious axe findings.
    await toArabic(page)
    await expect(page.getByRole('region', { name: 'هل ستحضر؟' })).toBeVisible()
    await checkPageA11y(page)
    await toEnglish(page)

    // The alternate is asked to attend in the director's place.
    const alternate = await pageAs(browser, 'headCredit')
    await alternate.goto(`/meetings/${created.id}`)
    await expect(
      alternate
        .getByRole('region', { name: 'Will you attend?' })
        .getByText('You are attending in place of Demo Director.'),
    ).toBeVisible()
    await alternate.context().close()

    // The secretary records the chair's answer, given by phone.
    await secretary.goto(`/meetings/${created.id}`)
    await secretary.getByRole('tab', { name: 'Invitations and replies' }).click()
    const table = secretary.getByRole('table', { name: 'Invitations and replies' })
    await row(table, 'Demo Board Member').getByRole('button', { name: 'Actions for Demo Board Member' }).click()
    await secretary.getByRole('menuitem', { name: 'Record an answer…' }).click()
    const record = secretary.getByRole('dialog', { name: 'Record an answer for Demo Board Member' })
    await expect(record).toContainText('Demo Board Member will be told that you recorded this answer.')
    await record.getByRole('radio', { name: 'Yes, in person' }).check()
    await record.getByRole('radio', { name: 'Phone' }).check()
    await record.getByRole('textbox', { name: /Note/ }).fill('Called the office this morning')
    await record.getByRole('button', { name: 'Record the answer' }).click()
    await expect(record).toBeHidden()
    await expect(secretary.getByText('The answer is recorded.')).toBeVisible()

    // The panel shows both answers and how each came; the alternate is listed under the director.
    await expect(row(table, 'Demo Director')).toContainText('Apologies — alternate attends')
    await expect(row(table, 'Demo Director')).toContainText('in memo')
    await expect(row(table, 'Demo Head of Credit')).toContainText('Alternate for Demo Director')
    await expect(row(table, 'Demo Board Member')).toContainText('Yes, in person')
    await expect(row(table, 'Demo Board Member')).toContainText('by phone')
    await expect(row(table, 'Demo Board Member')).toContainText('Recorded by Demo Company Secretary')
    await checkPageA11y(secretary)
    // At 400 % zoom (320 CSS pixels wide) only the table scrolls sideways, not the page (WCAG 1.4.10).
    await secretary.setViewportSize({ width: 320, height: 800 })
    await expect(table).toBeVisible()
    expect(
      await secretary.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth),
    ).toBe(0)
    await secretary.setViewportSize({ width: 1280, height: 720 })
    // The page is translated in place and keeps its tab, which is in the address.
    await expect(secretary).toHaveURL(/[?&]tab=replies(&|$)/)
    await toArabic(secretary)
    await expect(secretary.getByRole('tab', { name: 'الدعوات والردود' })).toHaveAttribute('aria-selected', 'true')
    await expect(secretary.getByRole('table', { name: 'الدعوات والردود' })).toBeVisible()
    await checkPageA11y(secretary)
    await toEnglish(secretary)
    await secretary.context().close()
  })
})
