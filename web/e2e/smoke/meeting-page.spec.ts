import type { Locator, Page } from '@playwright/test'
import { assertNoA11yViolations, expect, test } from '../fixtures'
import { pdf } from '../legacy'
import { checkPageA11y, committeeId, dayFromNow, MeetingApi, myId, type Draft } from '../meetings'
import { pageAs } from '../roles'

/**
 * The E03 members' journey (test plan T6–T8, flow P): the company secretary publishes a Board Risk Committee agenda
 * through the API (an item with a paper, a consent item, an item whose papers only the chair reads, an in-camera section
 * and the CFO as a guest). Demo Director, a member, reads it and opens the paper with the keyboard, asks to discuss the
 * consent item, and after revision 2 (the paper replaced) sees what changed. Every target on the page is at least 44 px,
 * and axe finds nothing serious in English or Arabic. The guest sees no members-only section.
 */
interface Meeting {
  id: string
  version: number
}

interface Member {
  role: string
  person?: { id: string; name: string }
}

/** The item of the agenda numbered and titled [line] ("5 Risk appetite"). */
function item(page: Page, line: string): Locator {
  return page.getByRole('listitem').filter({ hasText: line }).first()
}

/** Buttons and links of the agenda smaller than 44 by 44 CSS pixels (WCAG 2.5.5 as the design asks, flow P). */
async function smallTargets(page: Page): Promise<string[]> {
  const targets = page.locator('main section').getByRole('button')
  const small: string[] = []
  for (const target of await targets.all()) {
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

test.describe('as a committee member', () => {
  test.use({ role: 'director' })

  test('reads the agenda and a paper, asks to discuss a consent item and sees what changed; a guest sees no members-only section', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(240_000)
    const stamp = String(Date.now())
    const title = `E2E meeting page ${stamp}`

    // The secretary prepares and publishes the agenda through the API.
    const secretary = await pageAs(browser, 'secretary')
    const api = new MeetingApi(secretary)
    const brc = await committeeId(api, 'BRC')
    const chair = (await api.get<Member[]>(`/forums/${brc}/members`)).find((m) => m.role === 'CHAIR')?.person?.id
    if (!chair) throw new Error('the BRC has no chair')
    const guestPage = await pageAs(browser, 'headFinance')
    const guest = await myId(guestPage)
    const day = dayFromNow(21)
    const meeting = await api.send<Meeting>(`/forums/${brc}/meetings`, {
      kind: 'ORDINARY',
      title,
      startsAt: `${day}T10:00:00`,
      endsAt: `${day}T12:00:00`,
      timeZone: 'Asia/Dhaka',
      mode: 'IN_PERSON',
      location: 'Board Room, Level 12',
    })
    let draft = await api.send<Draft>(`/meetings/${meeting.id}/agenda`, { from: 'TEMPLATE' })
    const sectionId = (name: string) => {
      const found = draft.sections.find((s) => s.title === name)
      if (!found) throw new Error(`no section ${name}`)
      return found.id
    }
    const itemId = (name: string) => {
      const found = draft.sections.flatMap((s) => s.items).find((i) => i.title === name)
      if (!found) throw new Error(`no item ${name}`)
      return found.id
    }
    const add = async (section: string, name: string, purpose = 'INFORMATION', minutes?: number) => {
      draft = await api.send<Draft>(
        `/meetings/${meeting.id}/agenda/items`,
        { kind: 'ITEM', sectionId: sectionId(section), title: name, purpose, minutes },
        { version: draft.version },
      )
    }
    await add('For decision', 'Risk appetite statement', 'DECISION', 30)
    await add('For decision', 'Large exposures', 'DECISION', 20)
    await add('Consent items', 'Quarterly compliance report')
    draft = await api.send<Draft>(
      `/meetings/${meeting.id}/agenda/items/${itemId('Large exposures')}`,
      { title: 'Large exposures', purpose: 'DECISION', minutes: 20, restrictedTo: [chair] },
      { version: draft.version, method: 'PATCH' },
    )
    draft = await api.send<Draft>(
      `/meetings/${meeting.id}/agenda/sections`,
      { title: 'In camera', membersOnly: true },
      { version: draft.version },
    )
    await add('In camera', 'Chief Risk Officer objectives', 'DECISION', 15)
    const paper = await api.upload(meeting.id, 'risk-appetite.pdf', pdf('Risk appetite, version 1'))
    draft = await api.send<Draft>(
      `/meetings/${meeting.id}/agenda/items/${itemId('Risk appetite statement')}/papers`,
      { fileId: paper },
      { version: draft.version },
    )
    await api.send(`/meetings/${meeting.id}/guests`, { userId: guest, note: 'Chief Financial Officer' })
    draft = await api.send<Draft>(
      `/meetings/${meeting.id}/agenda/chair-agreement`,
      { onBehalf: true, note: 'By email' },
      { version: draft.version },
    )
    const current = await api.get<Meeting>(`/meetings/${meeting.id}`)
    await api.send(`/meetings/${meeting.id}/schedule`, {}, { version: current.version })
    draft = await api.send<Draft>(`/meetings/${meeting.id}/agenda/publish`, {}, { version: draft.version })

    // The director reads the agenda: the restricted item is a line, the in-camera section is there for a member.
    await app.visit(`/meetings/${meeting.id}`)
    await expect(page.getByRole('heading', { level: 2, name: 'Agenda' })).toBeVisible()
    await expect(page.getByText('Large exposures — papers restricted')).toBeVisible()
    await expect(page.getByText('Chief Risk Officer objectives')).toBeVisible()
    expect(await smallTargets(page)).toEqual([])

    // Opens the paper with the keyboard; Back to the agenda returns focus, and the paper is ticked as opened.
    const open = page.getByRole('button', { name: /^risk-appetite\.pdf · 1 page/ })
    await open.focus()
    await open.press('Enter')
    const viewer = page.getByRole('dialog', { name: 'risk-appetite.pdf' })
    await expect(viewer.getByRole('img', { name: 'risk-appetite.pdf' })).toBeVisible({ timeout: 30_000 })
    await assertNoA11yViolations(page)
    await viewer.getByRole('button', { name: 'Back to the agenda' }).press('Enter')
    await expect(viewer).toBeHidden()
    await expect(open).toBeFocused()
    await expect(item(page, 'Risk appetite statement')).toContainText('Opened')

    // Asks to discuss the consent item.
    const ask = page.getByRole('button', { name: /^Ask to discuss this item: \d+ Quarterly compliance report$/ })
    await ask.focus()
    await ask.press('Enter')
    const asking = page.getByRole('dialog', { name: /^Ask for item \d+ to be discussed separately\?$/ })
    await asking.getByRole('button', { name: 'Ask to discuss' }).press('Enter')
    await expect(page.getByText('Your request has been sent to the secretary and the chair.')).toBeVisible()
    await expect(item(page, 'Quarterly compliance report')).toContainText(
      'Lifted for discussion at the request of Demo Director.',
    )
    await checkPageA11y(page)

    // Revision 2 replaces the paper; the director sees what changed since they last looked.
    await api.replace(paper, 'risk-appetite.pdf', pdf('Risk appetite, version 2'))
    draft = await api.get<Draft>(`/meetings/${meeting.id}/agenda/draft`)
    await api.send(
      `/meetings/${meeting.id}/agenda/publish`,
      { note: 'Updated risk appetite paper' },
      { version: draft.version },
    )
    await app.visit(`/meetings/${meeting.id}`)
    await expect(page.getByText(/^The agenda changed on .+ \(revision 2\)\./)).toBeVisible()
    const show = page.getByRole('button', { name: 'Show changes' })
    await show.press('Enter')
    const changes = page.getByRole('dialog', { name: 'Changes since you last looked' })
    await expect(changes).toContainText('“Updated risk appetite paper”')
    await expect(changes).toContainText('Risk appetite statement: paper replaced')
    await assertNoA11yViolations(page)
    await page.keyboard.press('Escape')
    await expect(changes).toBeHidden()
    await expect(show).toBeFocused()

    // At 400 % zoom (320 CSS pixels wide) nothing scrolls sideways, the revision list included (WCAG 1.4.10).
    await page.setViewportSize({ width: 320, height: 800 })
    await expect(page.getByRole('combobox', { name: 'Revision of the agenda' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(
      0,
    )
    await page.setViewportSize({ width: 1280, height: 720 })

    // In Arabic, right to left, still without serious axe findings.
    await page.getByRole('button', { name: /Change language/ }).click()
    await page.getByRole('menuitemradio', { name: 'العربية' }).click()
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
    await checkPageA11y(page)
    await page.getByRole('button', { name: /تغيير اللغة|Change language/ }).click()
    await page.getByRole('menuitemradio', { name: 'English' }).click()
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr')

    // The guest reads the agenda without the in-camera section.
    await guestPage.goto(`/meetings/${meeting.id}`)
    await expect(guestPage.getByRole('heading', { level: 4, name: /^\d+ Risk appetite statement$/ })).toBeVisible()
    await expect(guestPage.getByText('Chief Risk Officer objectives')).toHaveCount(0)
    await expect(guestPage.getByText('In camera')).toHaveCount(0)
    await guestPage.context().close()
    await secretary.context().close()
  })
})
