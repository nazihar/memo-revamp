import type { Page } from '@playwright/test'
import {
  conditionAsDraftAction,
  markHeldSendingDrafts,
  meetingWithCondition,
  openFromMyActions,
  resolutionNumber,
  said,
} from '../actions'
import { expect, test } from '../fixtures'
import { checkPageA11y, committeeId, dayFromNow, MeetingApi, myId } from '../meetings'
import { pageAs } from '../roles'

/**
 * The E07 journeys (T12, test plan "action-items.spec"; flows AJ, AK, AL, AM, AN, AO).
 *
 * 1. **From a condition to verified:** on a Management Credit Committee meeting under way (prepared through the API, its
 *    item approved with a condition), the company secretary makes the condition an action for Demo Director from the
 *    decision panel, saved as a draft, and sends it by marking the meeting as held. The director finds it under My
 *    actions, posts an update and marks it done; the secretary verifies and closes it.
 * 2. **A new date asked for and agreed:** a direct action of the Board Risk Committee (whose type has the secretary agree
 *    a new date; the management committees' preset lets the owner change it, so a credit committee action would not
 *    ask), sent to Demo Director through the API. The director asks for a later date with a reason; it stays due on its
 *    date until the secretary agrees on the action page; it then reads "originally …, revised once".
 * 3. **The demo's actions** (the seed of E07 T12): the Board Risk Committee's tracker says each action's state in words
 *    (overdue, done to be verified, the revised date, the cancelled one), and the quarterly risk review's action taken
 *    report has its sections in order.
 * axe finds nothing serious on the pages and dialogs on the way.
 */

/** The demo's Board Risk Committee actions (`DemoActionsSeeder`). */
const RUPSHA = "Report on the Rupsha Steel group's other borrowings and the steel price outlook"
const INSURANCE = 'Brief the committee on the renewal of the cyber insurance cover'
const FUNDING = 'Refresh the contingency funding plan for the new liquidity stress scenarios'
const STRESS = 'Commission an independent validation of the liquidity stress model'

interface Action {
  id: string
  version: number
  reference?: string | null
}

/** Opens the action page at [actionUrl] and waits for its heading [title]. */
async function openAction(page: Page, actionUrl: string, title: string) {
  await page.goto(actionUrl)
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible({ timeout: 20_000 })
}

test.describe('a new date', () => {
  test.use({ role: 'secretary' })

  test('is asked for by the owner and agreed by the secretary', async ({ page, browser }) => {
    test.setTimeout(180_000)
    const title = `E2E new date ${String(Date.now())}`
    const director = await pageAs(browser, 'director')
    const api = new MeetingApi(page)
    const brc = await committeeId(api, 'BRC')
    const draft = await api.send<Action>(`/forums/${brc}/actions`, {
      title,
      sourceNote: 'Chair’s instruction (end-to-end test)',
      owner: { kind: 'PERSON', userId: await myId(director) },
      dueDate: dayFromNow(21),
    })
    await api.send<Action>(`/actions/${draft.id}/issue`, undefined, { version: draft.version })

    // The owner asks for a later date with a reason; until it is agreed, the date stays.
    await openAction(director, `/actions/${draft.id}`, title)
    await director.getByRole('button', { name: 'Ask for a new date' }).click()
    const ask = director.getByRole('dialog', { name: 'Ask for a new date' })
    await ask.getByLabel('New date').fill(dayFromNow(60))
    await ask.getByRole('textbox', { name: /Why/ }).fill('The second quarter’s figures come a month later.')
    await checkPageA11y(director)
    await ask.getByRole('button', { name: 'Ask for this date' }).click()
    await expect(said(director, 'Your request was sent. The date stays the same until it is agreed.')).toBeFocused()
    await expect(director.getByText(/New date requested: .+. Until it is agreed, the action stays due /)).toBeVisible()

    // The secretary agrees on the action page.
    await openAction(page, `/actions/${draft.id}`, title)
    const officer = page.getByRole('region', { name: 'For the committee’s officers' })
    await officer.getByRole('button', { name: 'Answer the date request' }).click()
    const answer = page.getByRole('dialog', { name: 'The owner asks for a new date' })
    await expect(answer).toContainText('Demo Director asks for')
    await answer.getByRole('radio', { name: 'Agree to the new date' }).check()
    await checkPageA11y(page)
    await answer.getByRole('button', { name: 'Answer' }).click()
    await expect(said(page, 'The new date was agreed. The owner is told.')).toBeFocused()

    // The owner reads the new date with where it started.
    await director.reload()
    await expect(director.getByText(/originally .+, revised once/).first()).toBeVisible()
    await director.context().close()
  })
})

test.describe('an action from a condition', () => {
  test.use({ role: 'secretary' })

  test('is raised at the meeting, sent when it is held, updated, done and verified', async ({ page, browser }) => {
    test.setTimeout(300_000)
    const stamp = String(Date.now())
    const condition = `Quarterly stock reports to the credit desk (${stamp}).`
    const meeting = await meetingWithCondition(browser, page, {
      title: `E2E actions ${stamp}`,
      item: `Working capital limit, Teesta Foods ${stamp}`,
      condition,
    })

    // The secretary makes the condition an action, as a draft, then sends it by marking the meeting as held.
    await conditionAsDraftAction(page, meeting)
    await checkPageA11y(page)
    await markHeldSendingDrafts(page)
    // The resolution is numbered by its job; the action takes the number into its source (a new version) before anyone acts.
    await resolutionNumber(page, meeting.item)

    // The director finds it under My actions and says how it is going.
    const director = await pageAs(browser, 'director')
    await openFromMyActions(director, condition)
    const actionUrl = director.url()
    const reference = ((await director.getByText(/^MCC A\d+\/\d{4} · /).textContent()) ?? '').split(' · ')[0] ?? ''
    expect(reference).toMatch(/^MCC A\d+\/\d{4}$/)
    await checkPageA11y(director)
    await director.getByRole('button', { name: 'Post an update' }).click()
    const update = director.getByRole('dialog', { name: 'Post an update' })
    await update.getByRole('textbox', { name: /What has happened\?/ }).fill('The first report is with the credit desk.')
    await update.getByRole('group', { name: 'How is it going?' }).getByRole('radio', { name: 'On track' }).check()
    await update.getByRole('button', { name: 'Post the update' }).click()
    await expect(said(director, 'Your update was posted.')).toBeFocused()

    // The director marks it done; the secretary verifies and closes it.
    await director.reload()
    await director.getByRole('button', { name: 'Mark as done' }).click()
    const done = director.getByRole('dialog', { name: 'Mark as done' })
    await done.getByRole('textbox', { name: /What was done/ }).fill('Both reports are in; the desk has checked them.')
    await done.getByRole('button', { name: 'Mark as done' }).click()
    await expect(said(director, /^Sent to the .+ secretary to verify\./)).toBeFocused()
    await expect(director.locator('[data-rag]').first()).toHaveText('Done, to be verified')
    await director.context().close()

    await openAction(page, actionUrl, condition)
    await page.getByRole('button', { name: 'Verify' }).click()
    const verify = page.getByRole('dialog', { name: 'Verify the action' })
    await expect(verify).toContainText('Done by Demo Director')
    await expect(verify).toContainText('Both reports are in; the desk has checked them.')
    await verify.getByRole('radio', { name: 'Yes: close the action' }).check()
    await verify.getByRole('textbox', { name: /Remark/ }).fill('Checked against the condition.')
    await checkPageA11y(page)
    await verify.getByRole('button', { name: 'Confirm' }).click()
    await expect(said(page, 'The action is closed. The owner is told.')).toBeFocused()
    await expect(page.locator('[data-rag]').first()).toHaveText('Closed')
    const timeline = page.getByRole('region', { name: 'Timeline' })
    await expect(timeline).toContainText('Checked against the condition.')
    await checkPageA11y(page)
  })
})

test.describe('the demo’s actions', () => {
  test.use({ role: 'secretary' })

  test('read in words on the tracker and in the next meeting’s action taken report', async ({ app, page }) => {
    test.setTimeout(180_000)
    const api = new MeetingApi(page)
    const brc = await committeeId(api, 'BRC')

    // The tracker, every status: each state in words with its icon, never by colour alone.
    await app.visit(`/committees/${brc}?tab=actions`)
    const tracker = page.getByRole('region', { name: /— Actions$/ })
    await tracker.getByRole('combobox', { name: 'Status' }).selectOption({ label: 'All' })
    const row = (title: string) => tracker.getByRole('row').filter({ hasText: title })
    await expect(row(RUPSHA).locator('[data-rag]')).toHaveText('Overdue')
    await expect(row(INSURANCE).locator('[data-rag]')).toHaveText('Done, to be verified')
    await expect(row(FUNDING)).toContainText('(rev. 1)')
    await expect(row(STRESS).locator('[data-rag]')).toHaveText('Cancelled')
    await checkPageA11y(page)

    // The quarterly review's matters arising: the report, its sections in order.
    const meetings = await api.get<{ items: { id: string; title: string }[] } | { id: string; title: string }[]>(
      `/forums/${brc}/meetings`,
    )
    const list = Array.isArray(meetings) ? meetings : meetings.items
    const review = list.find((m) => m.title === 'Quarterly risk review')
    if (!review) throw new Error('no quarterly risk review')
    await app.visit(`/meetings/${review.id}/action-report`)
    await expect(page.getByRole('heading', { level: 1, name: /^Action taken report — / })).toBeVisible()
    const headings = await page.getByRole('heading', { level: 2 }).allTextContents()
    const order = [
      'Proposed to close',
      'Overdue',
      'Open',
      'Closed since the last meeting',
      'Cancelled since the last meeting',
    ]
    const seen = order.map((h) => headings.findIndex((t) => t.includes(h)))
    expect(seen.every((i) => i >= 0)).toBe(true)
    expect([...seen].sort((a, b) => a - b)).toEqual(seen)
    await expect(page.getByText(RUPSHA)).toBeVisible()
    await checkPageA11y(page)
  })
})
