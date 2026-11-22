import type { Locator, Page } from '@playwright/test'
import { expect, test } from '../fixtures'
import { checkPageA11y, committeeId, MeetingApi, type Draft } from '../meetings'
import { pageAs } from '../roles'

/**
 * The E05 attendance journey (test plan T8 and T10, flows X and Y), on a Management Credit Committee meeting the company
 * secretary schedules through the API for now (it started 20 minutes ago, Dhaka time) with a published agenda, after
 * Demo Director, Demo Head of Credit and Demo Head of Finance said they will attend in person:
 * - the secretary starts the meeting from its page and lands on the register;
 * - marks everyone who said yes as present (quorate: 3 of the 4 members, 3 needed) and moves to the first decision item;
 * - records Demo Head of Finance leaving, which loses the quorum (announced politely), and their return, which restores it;
 * - reads the quorum of the item by name.
 * - (E06 T10, flow AA) records “Approved” on the limit item in the Decisions tab, marks the meeting as held after reading
 *   what will happen, and sees the resolution number the release job gives.
 * All with the keyboard; axe finds nothing serious on the register in English or Arabic, nor on the decision panel.
 */
interface Meeting {
  id: string
  version: number
}

const LIMIT = 'Working capital limit, Karnaphuli Traders'

/** A local date-time [minutes] from now in Asia/Dhaka (UTC+06:00 all year), as the meetings API takes it. */
function dhaka(minutes: number): string {
  return new Date(Date.now() + (6 * 60 + minutes) * 60_000).toISOString().slice(0, 16) + ':00'
}

/** The register's row whose row header is [name]. */
function row(page: Page, name: string): Locator {
  return page.getByRole('row').filter({ has: page.getByRole('rowheader', { name, exact: true }) })
}

async function press(target: Locator) {
  await target.focus()
  await target.press('Enter')
}

test.describe('attendance and quorum at the meeting', () => {
  test.use({ role: 'secretary' })

  test('the secretary starts the meeting, marks arrivals, moves to an item, and sees the quorum lost and restored', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(240_000)
    const title = `E2E attendance ${String(Date.now())}`

    // The secretary prepares the meeting through the API: two decision items, published.
    const api = new MeetingApi(page)
    const mcc = await committeeId(api, 'MCC')
    const created = await api.send<Meeting>(`/forums/${mcc}/meetings`, {
      kind: 'ORDINARY',
      title,
      startsAt: dhaka(-20),
      endsAt: dhaka(100),
      timeZone: 'Asia/Dhaka',
      mode: 'IN_PERSON',
      location: 'Credit Committee Room, Level 9',
    })
    let draft = await api.send<Draft>(`/meetings/${created.id}/agenda`, { from: 'TEMPLATE' })
    const decisions = draft.sections.find((s) => s.title === 'For decision')?.id
    if (!decisions) throw new Error('no section For decision')
    for (const name of [LIMIT, 'Watch-list accounts']) {
      draft = await api.send<Draft>(
        `/meetings/${created.id}/agenda/items`,
        { kind: 'ITEM', sectionId: decisions, title: name, purpose: 'DECISION', minutes: 20 },
        { version: draft.version },
      )
    }
    draft = await api.send<Draft>(
      `/meetings/${created.id}/agenda/chair-agreement`,
      { onBehalf: true, note: 'In person' },
      { version: draft.version },
    )
    const current = await api.get<Meeting>(`/meetings/${created.id}`)
    await api.send(`/meetings/${created.id}/schedule`, {}, { version: current.version })
    await api.send<Draft>(`/meetings/${created.id}/agenda/publish`, {}, { version: draft.version })
    for (const role of ['director', 'headCredit', 'headFinance'] as const) {
      const member = await pageAs(browser, role)
      await new MeetingApi(member).send(`/meetings/${created.id}/rsvp/mine`, { answer: 'IN_PERSON' }, { method: 'PUT' })
      await member.context().close()
    }

    // Starts the meeting from its page; the register is the tab the secretary lands on.
    await app.visit(`/meetings/${created.id}`)
    await press(page.getByRole('button', { name: /^Actions for / }))
    await page.getByRole('menuitem', { name: 'Start the meeting…' }).press('Enter')
    await press(page.getByRole('dialog').getByRole('button', { name: 'Start the meeting' }))
    await expect(page.getByRole('tab', { name: 'Attendance' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('heading', { name: /^Attendance — MCC \d+\/\d{4}$/ })).toBeVisible()
    const banner = page.getByRole('region', { name: 'Quorum' })
    await expect(banner).toContainText('Not quorate — the meeting is waiting for quorum')

    // Everyone who said yes is here: quorate.
    await press(page.getByRole('button', { name: 'Mark everyone who said yes as present (3)' }))
    const bulk = page.getByRole('dialog', { name: 'Mark everyone who said yes as present' })
    await press(bulk.getByRole('button', { name: 'Mark 3 people present' }))
    await expect(page.getByText('3 people are marked present.')).toBeVisible()
    await expect(banner).toContainText('Quorate — meeting: 3 of 4 present, 3 needed.')
    await expect(row(page, 'Demo Head of Finance')).toContainText(/In person since/)

    // The first decision item is in progress.
    await press(page.getByRole('button', { name: 'Change the item' }))
    await page.getByRole('menuitemradio', { name: new RegExp(`^\\d+ ${LIMIT}$`) }).press('Enter')
    await expect(page.getByText(new RegExp(`^Now: item \\d+ ${LIMIT}$`))).toBeVisible()
    await checkPageA11y(page)

    // The head of finance leaves: the quorum is lost, said in the banner and announced politely.
    await press(row(page, 'Demo Head of Finance').getByRole('button', { name: 'Left: Demo Head of Finance' }))
    await expect(row(page, 'Demo Head of Finance')).toContainText(/Left at .+, during item \d+/)
    await expect(banner.getByRole('status')).toHaveText(
      'Quorum lost: 2 of 4 present, 3 needed. No decision can be recorded until quorum is restored.',
    )
    await expect(banner).toContainText(/The meeting is not quorate since .+: 2 of 4 present, 3 needed\./)

    // They come back: restored.
    await press(row(page, 'Demo Head of Finance').getByRole('button', { name: 'Back in person: Demo Head of Finance' }))
    await expect(banner.getByRole('status')).toHaveText('Quorum restored: 3 of 4 present, 3 needed.')
    await expect(banner).toContainText('Quorate — meeting: 3 of 4 present, 3 needed.')

    // At 400 % zoom (320 CSS pixels wide) only the register's table scrolls sideways, not the page (WCAG 1.4.10).
    await page.setViewportSize({ width: 320, height: 800 })
    await expect(page.getByRole('table', { name: 'Members' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(
      0,
    )
    await page.setViewportSize({ width: 1280, height: 720 })

    // The quorum of the item by name.
    await press(banner.getByRole('button', { name: 'Quorum by item…' }))
    const byItem = page.getByRole('dialog', { name: 'Quorum by item' })
    await expect(byItem).toContainText(new RegExp(`Quorum for item \\d+ ${LIMIT}`))
    await expect(byItem).toContainText('Needed: 3 of 4.')
    await expect(byItem).toContainText(/Counted \(3\): .*Demo Director \(chair\)/)
    await expect(byItem).toContainText('Result: quorate')
    await checkPageA11y(page)
    await page.keyboard.press('Escape')
    await expect(byItem).toBeHidden()

    // In Arabic, right to left, the register still has no serious axe findings.
    await page.getByRole('button', { name: /Change language/ }).click()
    await page.getByRole('menuitemradio', { name: 'العربية' }).click()
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
    await expect(page.getByRole('table', { name: 'الأعضاء' })).toBeVisible()
    await checkPageA11y(page)
    await page.getByRole('button', { name: /تغيير اللغة|Change language/ }).click()
    await page.getByRole('menuitemradio', { name: 'English' }).click()
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr')

    // E06 T10 (flow AA): the secretary records the decision on the limit item in the Decisions tab, marks the meeting as
    // held, and the decision gets its resolution number (MCC/2026/001 with the committee type's default format).
    await press(page.getByRole('tab', { name: 'Decisions' }))
    const item = page.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: new RegExp(LIMIT) })
    await press(item)
    const decision = page.getByRole('region', { name: new RegExp(`^Item \\d+ — ${LIMIT}$`) })
    await expect(decision).toContainText(/Quorate for Item \d+: 3 of 4 voting members present, 3 needed\./)
    await decision.getByRole('radio', { name: 'Approved', exact: true }).check()
    await decision
      .getByLabel(/^Resolution \(as it will be minuted\)/)
      .fill('It was RESOLVED that the working capital limit be renewed.')
    await press(decision.getByRole('button', { name: 'Record the decision' }))
    await expect(page.getByText('The decision is recorded.')).toBeVisible()
    await expect(item).toContainText('Approved — numbered when the meeting ends')
    await checkPageA11y(page)

    // Marking the meeting as held says what will happen, then numbers the resolution (the release job, in seconds).
    await press(page.getByRole('button', { name: /^Actions for / }))
    await page.getByRole('menuitem', { name: 'Mark as held…' }).press('Enter')
    const held = page.getByRole('dialog', { name: /as held\?$/ })
    await expect(held).toContainText(/1 decision is recorded\. 1 is a resolution and will be numbered/)
    await press(held.getByRole('button', { name: 'Mark as held' }))
    await expect(async () => {
      await page.reload()
      await expect(
        page.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: new RegExp(LIMIT) }),
      ).toContainText(/Approved — MCC\/\d{4}\/\d{3}/, { timeout: 2_000 })
    }).toPass({ timeout: 60_000 })
  })
})
