import type { Locator, Page } from '@playwright/test'
import { expect, test } from '../fixtures'
import { checkPageA11y, committeeId, MeetingApi, myId, type Draft } from '../meetings'
import { pageAs } from '../roles'

/**
 * The E06 decisions journey (T12, flow AA) on a Management Credit Committee meeting the company secretary prepares through
 * the API: a limit for decision, a consent section of two items and a report for noting, published, started 20 minutes
 * ago (Dhaka time) with Demo Director, Demo Head of Credit and Demo Head of Finance present. In the Decisions tab, with the
 * keyboard:
 * - the limit is approved with a condition by a vote of 2–1, Demo Head of Finance's dissent recorded with their reason;
 * - the consent section is decided in one action (the policy approved, the report noted);
 * - Demo Head of Finance leaves, the meeting is not quorate, and the report for noting is recorded without a quorum under
 *   the committee type's override (the management committee allows it with a reason): "To be ratified";
 * - the meeting is marked as held and the two resolutions are numbered in agenda order.
 * axe finds nothing serious on the decision panel and the consent and override dialogs.
 */
interface Meeting {
  id: string
  version: number
}

const LIMIT = 'Working capital limit, Karnaphuli Traders'
const POLICY = 'Collateral policy: annual review, no changes'
const RECOVERY = 'Recovery report, October'
const PORTFOLIO = 'Portfolio quality note'

/** A local date-time [minutes] from now in Asia/Dhaka (UTC+06:00 all year), as the meetings API takes it. */
function dhaka(minutes: number): string {
  return new Date(Date.now() + (6 * 60 + minutes) * 60_000).toISOString().slice(0, 16) + ':00'
}

async function press(target: Locator) {
  await target.focus()
  await target.press('Enter')
}

function agendaItem(page: Page, title: string): Locator {
  return page.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: title })
}

function panel(page: Page, title: string): Locator {
  return page.getByRole('region', { name: ` — ${title}` })
}

test.describe('decisions at the meeting', () => {
  test.use({ role: 'secretary' })

  test('the secretary records a decision with a dissent, a consent section and an override, then the numbers', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(240_000)
    const title = `E2E decisions ${String(Date.now())}`

    // The meeting through the API: a decision item, a consent section of two, a report for noting; published and started.
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
    draft = await api.send<Draft>(
      `/meetings/${created.id}/agenda/sections`,
      { title: 'Consent items', kind: 'CONSENT', position: 2 },
      { version: draft.version },
    )
    const sectionId = (name: string) => {
      const id = draft.sections.find((s) => s.title === name)?.id
      if (!id) throw new Error(`no section ${name}`)
      return id
    }
    const items: [string, string, 'DECISION' | 'INFORMATION', string | undefined][] = [
      ['For decision', LIMIT, 'DECISION', 'That the committee renews the limit for a year.'],
      ['Consent items', POLICY, 'DECISION', 'That the committee approves the collateral policy without changes.'],
      ['Consent items', RECOVERY, 'INFORMATION', undefined],
      ['For noting', PORTFOLIO, 'INFORMATION', undefined],
    ]
    for (const [section, name, purpose] of items) {
      draft = await api.send<Draft>(
        `/meetings/${created.id}/agenda/items`,
        { kind: 'ITEM', sectionId: sectionId(section), title: name, purpose, minutes: 10 },
        { version: draft.version },
      )
    }
    draft = await api.send<Draft>(
      `/meetings/${created.id}/agenda/chair-agreement`,
      { onBehalf: true, note: 'In person' },
      { version: draft.version },
    )
    let current = await api.get<Meeting>(`/meetings/${created.id}`)
    await api.send(`/meetings/${created.id}/schedule`, {}, { version: current.version })
    await api.send<Draft>(`/meetings/${created.id}/agenda/publish`, {}, { version: draft.version })
    const members: string[] = []
    for (const role of ['director', 'headCredit', 'headFinance'] as const) {
      const member = await pageAs(browser, role)
      members.push(await myId(member))
      await member.context().close()
    }
    current = await api.get<Meeting>(`/meetings/${created.id}`)
    await api.send(`/meetings/${created.id}/start`, {}, { version: current.version })
    await api.send(`/meetings/${created.id}/attendance/arrivals`, { userIds: members, mode: 'IN_PERSON' })

    // The limit: approved with a condition, by a vote of 2–1, the head of finance's dissent recorded.
    await app.visit(`/meetings/${created.id}`)
    await press(page.getByRole('tab', { name: 'Decisions' }))
    await press(agendaItem(page, LIMIT))
    const limit = panel(page, LIMIT)
    await expect(limit).toContainText(/Quorate for Item \d+: 3 of 4 voting members present, 3 needed\./)
    await limit.getByRole('radio', { name: 'Approved with conditions' }).check()
    await limit
      .getByLabel(/^Resolution \(as it will be minuted\)/)
      .fill('It was RESOLVED that the working capital limit be renewed for a year, on the condition below.')
    await limit.getByRole('textbox', { name: 'Condition 1' }).fill('Quarterly stock reports to the credit desk.')
    await press(limit.getByRole('button', { name: /^Record a vote/ }))
    await limit.getByRole('textbox', { name: 'For', exact: true }).fill('2')
    await limit.getByRole('textbox', { name: 'Against', exact: true }).fill('1')
    await press(limit.getByRole('button', { name: /^Dissent and abstentions/ }))
    const finance = limit.getByRole('group', { name: 'Demo Head of Finance' })
    await finance.getByRole('radio', { name: 'Dissented' }).check()
    await limit.getByLabel('Reason they asked to be recorded (optional)').fill('The leverage has risen for two years.')
    await checkPageA11y(page)
    await press(limit.getByRole('button', { name: 'Record the decision' }))
    await expect(page.getByText('The decision is recorded.')).toBeVisible()
    await expect(agendaItem(page, LIMIT)).toContainText('Approved with conditions — numbered when the meeting ends')
    await expect(limit).toContainText('Vote: for 2, against 1.')
    await expect(limit).toContainText(
      'Demo Head of Finance asked that their dissent be recorded: The leverage has risen for two years.',
    )

    // The consent section in one action: what each item becomes is said first.
    await press(page.getByRole('button', { name: 'Decide the consent items together' }))
    const consent = page.getByRole('dialog', { name: 'Decide the consent items together' })
    await expect(consent).toContainText(/Item \d+ will be Approved\./)
    await expect(consent).toContainText(/Item \d+ will be Noted\./)
    await checkPageA11y(page)
    await press(consent.getByRole('button', { name: 'Decide together' }))
    await expect(page.getByText('The consent items are decided.')).toBeVisible()

    // The head of finance leaves: not quorate. The report is noted under the override, to be ratified.
    await page.getByRole('tab', { name: 'Attendance' }).click()
    const financeRow = page
      .getByRole('row')
      .filter({ has: page.getByRole('rowheader', { name: 'Demo Head of Finance', exact: true }) })
    await press(financeRow.getByRole('button', { name: 'Left: Demo Head of Finance' }))
    await expect(financeRow).toContainText(/Left at /)
    await page.getByRole('tab', { name: 'Decisions' }).click()
    await press(agendaItem(page, PORTFOLIO))
    const portfolio = panel(page, PORTFOLIO)
    await portfolio.getByRole('radio', { name: 'Noted' }).check()
    await press(portfolio.getByRole('button', { name: 'Record without a quorum…' }))
    const override = page.getByRole('dialog', { name: 'Record without quorum' })
    await expect(override).toContainText('This item is not quorate: 2 of 3 needed.')
    await override.getByRole('textbox', { name: /^Reason/ }).fill('The report goes to the regulator today.')
    await checkPageA11y(page)
    await press(override.getByRole('button', { name: 'Record without quorum' }))
    await expect(override).toBeHidden()
    await expect(agendaItem(page, PORTFOLIO)).toContainText('To be ratified')

    // Marked as held: the two resolutions are numbered in agenda order (the limit, then the policy).
    await press(page.getByRole('button', { name: /^Actions for / }))
    await page.getByRole('menuitem', { name: 'Mark as held…' }).press('Enter')
    const held = page.getByRole('dialog', { name: /as held\?$/ })
    await expect(held).toContainText(
      /4 decisions are recorded\. 2 are resolutions and will be numbered in agenda order/,
    )
    await press(held.getByRole('button', { name: 'Mark as held' }))
    const numberOf = async (name: string) => {
      const text = (await agendaItem(page, name).textContent()) ?? ''
      return Number(/MCC\/\d{4}\/(\d{3})/.exec(text)?.[1])
    }
    await expect(async () => {
      await page.reload()
      await expect(agendaItem(page, POLICY)).toContainText(/Approved — MCC\/\d{4}\/\d{3}/, { timeout: 2_000 })
      await expect(agendaItem(page, LIMIT)).toContainText(/Approved with conditions — MCC\/\d{4}\/\d{3}/, {
        timeout: 2_000,
      })
    }).toPass({ timeout: 60_000 })
    expect(await numberOf(POLICY)).toBe((await numberOf(LIMIT)) + 1)
    await expect(agendaItem(page, RECOVERY)).toContainText('Noted')
  })
})
