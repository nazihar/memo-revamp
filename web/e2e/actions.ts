import type { Browser, Locator, Page } from '@playwright/test'
import { expect } from './fixtures'
import { committeeId, MeetingApi, myId, type Draft } from './meetings'
import { pageAs } from './roles'

/**
 * Helpers of the Phase 4 E07 journeys (`action-items.spec`, `committee-cycle.spec`): a Management Credit Committee meeting
 * under way with one item decided with a condition, prepared by the company secretary through the API (the E06 journeys
 * record decisions through the screens), and the people's actions on an action's page.
 */

interface Meeting {
  id: string
  version: number
}

/** A local date-time [minutes] from now in Asia/Dhaka (UTC+06:00 all year), as the meetings API takes it. */
export function dhaka(minutes: number): string {
  return new Date(Date.now() + (6 * 60 + minutes) * 60_000).toISOString().slice(0, 16) + ':00'
}

/** A meeting under way, its decided item and the condition the committee set. */
export interface DecidedMeeting {
  meetingId: string
  itemId: string
  item: string
  condition: string
}

/**
 * The secretary's MCC meeting [title], started 20 minutes ago (Dhaka time) with Demo Director, Demo Head of Credit and
 * Demo Head of Finance present, whose item [item] is approved with the condition [condition] (not yet numbered: the
 * meeting is not yet held).
 */
export async function meetingWithCondition(
  browser: Browser,
  secretary: Page,
  { title, item, condition }: { title: string; item: string; condition: string },
): Promise<DecidedMeeting> {
  const api = new MeetingApi(secretary)
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
  const section = draft.sections.find((s) => s.title === 'For decision')?.id
  if (!section) throw new Error('no section For decision')
  draft = await api.send<Draft>(
    `/meetings/${created.id}/agenda/items`,
    { kind: 'ITEM', sectionId: section, title: item, purpose: 'DECISION', minutes: 15 },
    { version: draft.version },
  )
  const itemId = draft.sections.flatMap((s) => s.items).find((i) => i.title === item)?.id
  if (!itemId) throw new Error('the item is not on the agenda')
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
  await api.send(
    `/meetings/${created.id}/items/${itemId}/decision`,
    {
      outcome: 'APPROVED_WITH_CONDITIONS',
      text: `It was RESOLVED that the ${item.toLowerCase()} be approved, on the condition below.`,
      conditions: [condition],
    },
    { method: 'PUT' },
  )
  return { meetingId: created.id, itemId, item, condition }
}

/**
 * On the meeting's Decisions tab, the secretary makes the decision's first condition an action for Demo Director, due in
 * a month, saved as a draft (actions raised during the meeting wait for it to be held).
 */
export async function conditionAsDraftAction(page: Page, meeting: DecidedMeeting): Promise<void> {
  await page.goto(`/meetings/${meeting.meetingId}`)
  await page.getByRole('tab', { name: 'Decisions' }).press('Enter')
  await page.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: meeting.item }).press('Enter')
  const panel = page.getByRole('region', { name: ` — ${meeting.item}` })
  await panel.getByRole('button', { name: 'Make condition 1 an action' }).click()
  const form = page.getByRole('dialog', { name: /^New action — from .+, condition 1$/ })
  await expect(form.getByRole('textbox', { name: 'What is to be done' })).toHaveValue(meeting.condition)
  await form.getByRole('radio', { name: 'A person' }).check()
  await form.getByRole('combobox', { name: 'Who' }).fill('Demo Dir')
  await form.getByRole('option', { name: /Demo Director/ }).click()
  if (await form.getByRole('radio', { name: 'On a date' }).isVisible()) {
    await form.getByRole('radio', { name: 'On a date' }).check()
  }
  await form.getByRole('button', { name: 'In a month' }).click()
  await expect(form.getByRole('button', { name: 'Save and send to the owner' })).toHaveCount(0)
  await form.getByRole('button', { name: 'Save as draft' }).click()
  await expect(page.getByText(`Saved as a draft: ${meeting.condition}.`)).toBeVisible()
  await expect(panel.getByText('Draft', { exact: true }).first()).toBeVisible()
}

/** Marks the meeting as held with its drafts sent to their owners (ticked by default), as the toast says. */
export async function markHeldSendingDrafts(page: Page): Promise<void> {
  await page.getByRole('button', { name: /^Actions for / }).press('Enter')
  await page.getByRole('menuitem', { name: 'Mark as held…' }).press('Enter')
  const held = page.getByRole('dialog', { name: /as held\?$/ })
  const send = held.getByRole('checkbox', { name: /^Send the \d+ draft actions? to (its owner|their owners) now$/ })
  await expect(send).toBeChecked()
  await held.getByRole('button', { name: 'Mark as held' }).press('Enter')
  await expect(held).toBeHidden()
  await expect(
    page.getByText(/^The meeting is held\. \d+ actions? (was|were) sent to (its owner|their owners)\.$/).first(),
  ).toBeVisible()
}

/** The action called [title] in [person]'s My actions; opens it. */
export async function openFromMyActions(page: Page, title: string): Promise<Locator> {
  await page.goto('/inbox?tab=actions')
  const row = page.getByRole('link').filter({ hasText: title })
  await expect(row).toBeVisible()
  await row.click()
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible({ timeout: 20_000 })
  return row
}

/** The live region an action page announces a change in, focused after the change. */
export function said(page: Page, text: string | RegExp): Locator {
  return page.getByRole('status').filter({ hasText: text })
}

/**
 * Waits until the decision on [item] has its resolution number (the `minutes-release` job numbers it after the meeting is
 * marked as held, and the actions raised from it then take the number into their source); the number.
 */
export async function resolutionNumber(page: Page, item: string): Promise<string> {
  const line = page.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: item })
  let number = ''
  await expect(async () => {
    await page.reload()
    number = /[A-Z]+\/\d{4}\/\d{3}/.exec((await line.textContent()) ?? '')?.[0] ?? ''
    expect(number).not.toBe('')
  }).toPass({ timeout: 60_000 })
  return number
}
