import type { Locator, Page } from '@playwright/test'
import { assertNoA11yViolations, expect, test } from '../fixtures'
import { pdf } from '../legacy'
import { committeeId, dayFromNow, MeetingApi, type Draft } from '../meetings'
import { pageAs } from '../roles'

/**
 * The E04 journey (test plan T6–T9, flows S, T and U), on a Management Credit Committee agenda the company secretary
 * publishes through the API (an item with a paper, and a second item):
 * - Demo Head of Finance, a member, declares an interest on the first item with the keyboard: its line becomes the
 *   recused line without its paper, and they confirm the review of the agenda;
 * - the secretary records Demo Head of Credit's declaration on the second item "by email", asking the chair to decide;
 * - Demo Director, the chair, decides in the app that they may take part, and Demo Head of Credit reads the item in full;
 * - the organisation administrator reads both in the register of declarations and exports it.
 * Every target on the member's page is at least 44 px, nothing scrolls sideways at 320 px, and axe finds nothing serious
 * in English or Arabic.
 */
interface Meeting {
  id: string
  version: number
  label: string
}

const LOAN = 'Term loan, Delta Textiles'
const LIMITS = 'Market risk limits'

/** The agenda item whose level-4 heading holds [title]. */
function item(page: Page, title: string): Locator {
  return page
    .getByRole('listitem')
    .filter({ has: page.getByRole('heading', { level: 4 }).filter({ hasText: title }) })
    .first()
}

/** Buttons of the agenda and the declarations panel smaller than 44 by 44 CSS pixels (flow S). */
async function smallTargets(page: Page): Promise<string[]> {
  const small: string[] = []
  for (const target of await page.locator('main section').getByRole('button').all()) {
    if (!(await target.isVisible())) continue
    const box = await target.boundingBox()
    if (box && (box.height < 44 || box.width < 44)) {
      small.push(
        `${(await target.getAttribute('aria-label')) ?? (await target.innerText())}: ${String(box.width)}×${String(box.height)}`,
      )
    }
  }
  return small
}

/** The visible text of the option of [select] containing [text] (agenda numbers come from the server). */
async function optionLabel(select: Locator, text: string): Promise<string> {
  const label = await select.locator('option', { hasText: text }).first().textContent()
  if (!label) throw new Error(`no option ${text}`)
  return label.trim()
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

test.describe('declarations of interest', () => {
  test.use({ role: 'headFinance' })

  test('a member declares and confirms the review; the secretary records one by email and the chair rules; the register exports them', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(300_000)
    const stamp = String(Date.now())
    const title = `E2E declarations ${stamp}`

    // The secretary prepares and publishes the agenda through the API.
    const secretary = await pageAs(browser, 'secretary')
    const api = new MeetingApi(secretary)
    const mcc = await committeeId(api, 'MCC')
    const day = dayFromNow(21)
    const created = await api.send<Meeting>(`/forums/${mcc}/meetings`, {
      kind: 'ORDINARY',
      title,
      startsAt: `${day}T14:00:00`,
      endsAt: `${day}T16:00:00`,
      timeZone: 'Asia/Dhaka',
      mode: 'IN_PERSON',
      location: 'Credit Committee Room, Level 9',
    })
    let draft = await api.send<Draft>(`/meetings/${created.id}/agenda`, { from: 'TEMPLATE' })
    const forDecision = draft.sections.find((s) => s.title === 'For decision')?.id
    if (!forDecision) throw new Error('no section For decision')
    for (const name of [LOAN, LIMITS]) {
      draft = await api.send<Draft>(
        `/meetings/${created.id}/agenda/items`,
        { kind: 'ITEM', sectionId: forDecision, title: name, purpose: 'DECISION', minutes: 20 },
        { version: draft.version },
      )
    }
    const loanId = draft.sections.flatMap((s) => s.items).find((i) => i.title === LOAN)?.id
    if (!loanId) throw new Error(`no item ${LOAN}`)
    const paper = await api.upload(created.id, 'delta-textiles.pdf', pdf('Term loan, Delta Textiles'))
    draft = await api.send<Draft>(
      `/meetings/${created.id}/agenda/items/${loanId}/papers`,
      { fileId: paper },
      { version: draft.version },
    )
    draft = await api.send<Draft>(
      `/meetings/${created.id}/agenda/chair-agreement`,
      { onBehalf: true, note: 'By email' },
      { version: draft.version },
    )
    const scheduled = await api.get<Meeting>(`/meetings/${created.id}`)
    await api.send(`/meetings/${created.id}/schedule`, {}, { version: scheduled.version })
    await api.send<Draft>(`/meetings/${created.id}/agenda/publish`, {}, { version: draft.version })
    const { label } = await api.get<Meeting>(`/meetings/${created.id}`)

    // Flow S: the member sees the panel, and the item's paper until they declare.
    await app.visit(`/meetings/${created.id}`)
    const panel = page.getByRole('region', { name: 'Declarations of interest' })
    await expect(panel).toContainText('Please read the agenda and tell us about any interest.')
    await expect(item(page, LOAN).getByRole('button', { name: /^delta-textiles\.pdf · / })).toBeVisible()
    expect(await smallTargets(page)).toEqual([])

    // Declares with the keyboard: leaving the room is the default.
    const declare = page.getByRole('button', { name: new RegExp(`^Declare an interest: \\d+ ${LOAN}$`) })
    await declare.focus()
    await declare.press('Enter')
    const sheet = page.getByRole('dialog', { name: new RegExp(`^Declare an interest — \\d+ ${LOAN}$`) })
    await expect(
      sheet.getByRole('radio', { name: /I will leave the room for this item\. \(recommended\)/ }),
    ).toBeChecked()
    const nature = sheet.getByRole('textbox', { name: 'What is your interest?' })
    await nature.focus()
    await page.keyboard.type('My brother-in-law is a director of Delta Textiles.')
    await assertNoA11yViolations(page)
    await sheet.getByRole('button', { name: 'Declare', exact: true }).press('Enter')
    await expect(
      page.getByText('Thank you. The secretary and the chair have been told. You will not see this item’s papers.'),
    ).toBeVisible()

    // The recused line: the title and why, no paper, and "My declaration" keeps the focus.
    await expect(
      page.getByRole('heading', { level: 4, name: new RegExp(`^\\d+ ${LOAN} — You declared an interest$`) }),
    ).toBeVisible()
    const recused = item(page, LOAN)
    await expect(recused).toContainText(
      'You will be asked to leave the room for this item. Its papers are not shown to you.',
    )
    await expect(recused.getByRole('button', { name: /delta-textiles\.pdf/ })).toHaveCount(0)
    // The panel lists the same declaration with its own "My declaration"; the item's one keeps the focus.
    await expect(recused.getByRole('button', { name: new RegExp(`^My declaration: \\d+ ${LOAN}$`) })).toBeFocused()

    // Reviews the agenda and confirms: the declared item says what was declared.
    await panel.getByRole('button', { name: 'Review and declare' }).press('Enter')
    const review = page.getByRole('dialog', { name: /^Review the agenda for .+ — declarations of interest$/ })
    const list = review.getByRole('list', { name: 'Items to review' })
    await expect(
      list.getByRole('button', { name: new RegExp(`^Declared — leaving the room: \\d+ ${LOAN}$`) }),
    ).toBeVisible()
    await expect(list.getByRole('button', { name: new RegExp(`^I have an interest: \\d+ ${LIMITS}$`) })).toBeVisible()
    await assertNoA11yViolations(page)
    await review.getByRole('button', { name: 'I have declared my interests in this agenda' }).press('Enter')
    await expect(page.getByText('Thank you. Your review of the agenda is recorded.')).toBeVisible()
    await expect(panel).toContainText('You confirmed your declarations for revision 1 of the agenda.')
    await expect(page.getByRole('heading', { level: 2, name: 'Declarations of interest' })).toBeFocused()
    expect(await smallTargets(page)).toEqual([])
    await app.checkA11y()

    // At 400 % zoom (320 CSS pixels wide) nothing scrolls sideways (WCAG 1.4.10).
    await page.setViewportSize({ width: 320, height: 800 })
    await expect(recused).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(
      0,
    )
    await page.setViewportSize({ width: 1280, height: 720 })

    // In Arabic, right to left, still without serious axe findings.
    await toArabic(page)
    await app.checkA11y()
    await toEnglish(page)

    // Flow T: the secretary sees the declaration and records Demo Head of Credit's, made by email.
    await secretary.goto(`/meetings/${created.id}`)
    await secretary.getByRole('tab', { name: 'Declarations' }).press('Enter')
    const officers = secretary.getByRole('region', { name: 'Declarations of interest' })
    await expect(officers).toContainText('Reviewed: 1 of')
    const loanBlock = officers.getByRole('region', { name: new RegExp(`^Item \\d+ ${LOAN}$`) })
    await expect(loanBlock).toContainText('Demo Head of Finance')
    await expect(loanBlock).toContainText('Leaving the room')
    await expect(loanBlock).toContainText('“My brother-in-law is a director of Delta Textiles.”')
    await officers.getByRole('button', { name: 'Record a declaration…' }).press('Enter')
    const record = secretary.getByRole('dialog', { name: 'Record a declaration of interest' })
    const member = record.getByRole('combobox', { name: 'Member' })
    await member.selectOption({ label: await optionLabel(member, 'Demo Head of Credit') })
    const itemSelect = record.getByRole('combobox', { name: 'Item' })
    await itemSelect.selectOption({ label: await optionLabel(itemSelect, LIMITS) })
    await record.getByRole('textbox', { name: 'Their interest' }).fill('Holds shares in a bank in the limit review.')
    await record.getByRole('radio', { name: 'By email' }).press('Space')
    await record.getByRole('textbox', { name: /^Note/ }).fill(`email of ${day}`)
    await record.getByRole('radio', { name: 'Ask the chair to decide' }).press('Space')
    await assertNoA11yViolations(secretary)
    await record.getByRole('button', { name: 'Record', exact: true }).press('Enter')
    await expect(secretary.getByText('Recorded. Demo Head of Credit has been told.')).toBeVisible()
    const limitsBlock = officers.getByRole('region', { name: new RegExp(`^Item \\d+ ${LIMITS}$`) })
    await expect(limitsBlock).toContainText('Waiting for a decision')
    await expect(limitsBlock).toContainText('By email, recorded by')
    await expect(limitsBlock).toContainText('Waiting for Demo Director to decide.')
    await assertNoA11yViolations(secretary)

    // The chair decides in the app that they may take part.
    const chair = await pageAs(browser, 'director')
    await chair.goto(`/meetings/${created.id}`)
    await chair.getByRole('tab', { name: 'Declarations' }).press('Enter')
    const chairs = chair.getByRole('region', { name: 'Declarations of interest' })
    const toDecide = chairs.getByRole('region', { name: new RegExp(`^Item \\d+ ${LIMITS}$`) })
    await expect(toDecide).toContainText('Waiting for your decision.')
    await toDecide
      .getByRole('button', { name: /^Decide on Demo Head of Credit’s declaration, item \d+$/ })
      .press('Enter')
    const ruling = chair.getByRole('dialog', { name: /^A declaration for .+ — your decision is needed$/ })
    await expect(ruling).toContainText(
      'Demo Head of Credit asks whether they may take part. Until you decide, the papers are withheld from them.',
    )
    await ruling.getByRole('textbox', { name: /^Note/ }).fill('Holding below 0.1 per cent; not material.')
    await assertNoA11yViolations(chair)
    await ruling.getByRole('button', { name: 'They may take part' }).press('Enter')
    await expect(chair.getByText('Demo Head of Credit may take part. They have been told.')).toBeVisible()
    await expect(toDecide).toContainText('Taking part')
    await chair.context().close()

    // Demo Head of Credit reads the item in full, with the chair's decision.
    const vice = await pageAs(browser, 'headCredit')
    await vice.goto(`/meetings/${created.id}`)
    await expect(item(vice, LIMITS)).toContainText('You declared an interest. The chair decided you may take part.')
    await vice.context().close()
    await secretary.context().close()

    // Flow U: the organisation administrator reads both in the register and exports it.
    const admin = await pageAs(browser, 'tenantAdmin')
    await admin.goto(`/declarations?forumId=${mcc}`)
    await expect(admin.getByRole('heading', { level: 1, name: 'Register of declarations of interest' })).toBeVisible()
    const rows = admin.getByRole('row').filter({ hasText: label })
    await expect(rows.filter({ hasText: 'Demo Head of Finance' })).toContainText('Leaving the room')
    await expect(rows.filter({ hasText: 'Demo Head of Credit' })).toContainText('Taking part')
    await assertNoA11yViolations(admin)
    const download = admin.waitForEvent('download')
    await admin.getByRole('button', { name: 'Export to Excel' }).press('Enter')
    expect((await download).suggestedFilename()).toMatch(/\.xlsx$/)
    await expect(admin.getByText('The workbook has been downloaded.')).toBeVisible()
    await toArabic(admin)
    await assertNoA11yViolations(admin)
    await toEnglish(admin)
    await admin.context().close()
  })
})
