import type { Locator, Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { expect, test } from '../fixtures'
import { checkPageA11y, committeeId, MeetingApi, type Draft } from '../meetings'
import { pageAs } from '../roles'

/**
 * The E06 minutes journey (T11, flows AD–AG) on a Management Credit Committee meeting (approved by circulation) the
 * company secretary prepares and holds through the API, with two items for discussion (no memo, so no decision is
 * needed before the draft goes to the chair):
 * - the secretary makes the draft from the committee's template and sends it to the chair;
 * - the chair (Demo Director) sends it back with a comment, which the secretary reads on the minutes page;
 * - sent again, the chair agrees and the minutes are circulated; Demo Head of Credit says "No comments";
 * - the secretary ends the comment period early, and the chair approves the minutes with their authenticator.
 * The editor (ONLYOFFICE) and the readers' PDF copies are not opened: the smoke stack has no document server, so the
 * copies and the record are not made here (the nightly stack has one; the record is covered by the backend's tests).
 * axe finds nothing serious on the secretary's page, the chair's review and the approval, in English and in Arabic.
 */
interface Meeting {
  id: string
  version: number
  label?: string | null
}

/** A local date-time [minutes] from now in Asia/Dhaka (UTC+06:00 all year), as the meetings API takes it. */
function dhaka(minutes: number): string {
  return new Date(Date.now() + (6 * 60 + minutes) * 60_000).toISOString().slice(0, 16) + ':00'
}

async function press(target: Locator) {
  await target.focus()
  await target.press('Enter')
}

/** Reads the minutes page again until [text] shows (the draft job and the structure check run in the background). */
async function until(page: Page, path: string, text: string | RegExp) {
  await expect
    .poll(
      async () => {
        await page.goto(path)
        return page.getByText(text).first().isVisible()
      },
      { timeout: 60_000, intervals: [2_000] },
    )
    .toBe(true)
}

test.describe('drafting, reviewing, circulating and approving the minutes', () => {
  test.use({ role: 'secretary' })

  test('the secretary drafts, the chair sends back then agrees, a member has no comments, the chair approves', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(300_000)
    const title = `E2E minutes ${String(Date.now())}`
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
    const section = draft.sections.find((s) => s.title === 'For decision')?.id
    if (!section) throw new Error('no section For decision')
    for (const name of ['Portfolio review', 'Recovery update']) {
      draft = await api.send<Draft>(
        `/meetings/${created.id}/agenda/items`,
        { kind: 'ITEM', sectionId: section, title: name, purpose: 'DISCUSSION', minutes: 20 },
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
    current = await api.get<Meeting>(`/meetings/${created.id}`)
    await api.send(`/meetings/${created.id}/start`, {}, { version: current.version })
    current = await api.get<Meeting>(`/meetings/${created.id}`)
    await api.send(`/meetings/${created.id}/end`, {}, { version: current.version })
    const minutes = `/meetings/${created.id}/minutes`

    // The secretary makes the draft from the meeting page's Minutes card.
    await app.visit(`/meetings/${created.id}`)
    await press(page.getByRole('region', { name: 'Minutes' }).getByRole('link', { name: 'Open the minutes' }))
    await expect(page.getByRole('heading', { name: /^Minutes — Management Credit Committee/ })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Steps' })).toContainText('Comment period ended')
    await press(page.getByRole('button', { name: 'Make the draft' }))
    await expect(page.getByText(/^Draft ready \(version 1, /)).toBeVisible({ timeout: 60_000 })
    // The steps wait until the draft's blocks are checked (the page polls meanwhile).
    await expect(page.getByRole('button', { name: 'Send to the chair' })).toBeEnabled({ timeout: 60_000 })
    await checkPageA11y(page)
    await press(page.getByRole('button', { name: 'Send to the chair' }))
    await press(
      page.getByRole('dialog', { name: 'Send the draft to the chair?' }).getByRole('button', { name: 'Send' }),
    )
    await expect(page.getByText(/^The draft is with Demo Director for review\.$/)).toBeVisible()

    // The chair sends it back with a comment.
    const chair = await pageAs(browser, 'director')
    await chair.goto(minutes)
    const review = chair.getByRole('region', { name: /— ready for your review$/ })
    await expect(review).toBeVisible()
    await checkPageA11y(chair)
    await review.getByRole('textbox', { name: /Comments to the secretary/ }).fill('Add the recovery figures')
    await press(review.getByRole('button', { name: 'Send back with comments' }))
    await chair.waitForURL((url) => url.pathname === `/meetings/${created.id}`)

    // The secretary reads the comment and sends the draft again; the chair agrees and it is circulated.
    await page.goto(minutes)
    await expect(page.getByText('Demo Director sent the draft back', { exact: false })).toBeVisible()
    await expect(page.getByText('“Add the recovery figures”')).toBeVisible()
    await press(page.getByRole('button', { name: 'Send to the chair' }))
    await press(
      page.getByRole('dialog', { name: 'Send the draft to the chair?' }).getByRole('button', { name: 'Send' }),
    )
    await chair.goto(minutes)
    await press(
      chair
        .getByRole('region', { name: /— ready for your review$/ })
        .getByRole('button', { name: 'Ready to circulate' }),
    )
    await expect(chair.getByText('You agreed the draft. It is now circulated for comments.')).toBeVisible()

    // A member says "No comments".
    const member = await pageAs(browser, 'headCredit')
    await member.goto(minutes)
    const card = member.getByRole('region', { name: /^Draft minutes of Management Credit Committee/ })
    await expect(card).toContainText(/Please send any comments by /)
    await press(card.getByRole('button', { name: 'No comments' }))
    await press(
      member
        .getByRole('dialog', { name: 'Tell the secretary you have no comments on these minutes?' })
        .getByRole('button', { name: 'No comments' }),
    )
    await expect(card.getByText(/^You told the secretary you have no comments\./)).toBeVisible()
    await member.context().close()

    // The secretary sees the answer and ends the comment period early.
    await until(page, minutes, 'No comments: Demo Head of Credit')
    await checkPageA11y(page)
    await press(page.getByRole('button', { name: 'End the comment period now…' }))
    const end = page.getByRole('dialog', { name: 'End the comment period now?' })
    await end.getByRole('textbox', { name: /Reason/ }).fill('The chair asked to approve today')
    await press(end.getByRole('button', { name: 'End the comment period' }))
    await expect(page.getByText(/^Waiting for Demo Director to approve\.$/)).toBeVisible()

    // The chair approves with their authenticator; in Arabic the approval reads right to left without findings.
    await chair.goto(minutes)
    const approve = chair.getByRole('region', { name: /^Approve the minutes of / })
    await expect(approve).toContainText('When you approve, the minutes are locked and kept as the permanent record.')
    await checkPageA11y(chair)
    await chair.getByRole('button', { name: /Change language/ }).click()
    await chair.getByRole('menuitemradio', { name: 'العربية' }).click()
    await expect(chair.locator('html')).toHaveAttribute('dir', 'rtl')
    await expect(chair.getByRole('button', { name: 'اعتماد المحضر' })).toBeVisible()
    await checkPageA11y(chair)
    await chair.getByRole('button', { name: /تغيير اللغة|Change language/ }).click()
    await chair.getByRole('menuitemradio', { name: 'English' }).click()
    await press(approve.getByRole('button', { name: 'Approve the minutes' }))
    await confirmIfAsked(chair, demoSecret('MEMO_DEMO_TOTP_SECRET'), chair.getByText(/^Approved on /))
    await expect(chair.getByText(/^Approved on /)).toBeVisible()
    await expect(chair.getByRole('heading', { name: 'The record' })).toBeVisible()
    await chair.context().close()
  })
})
