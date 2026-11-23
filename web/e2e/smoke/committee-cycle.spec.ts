import {
  conditionAsDraftAction,
  markHeldSendingDrafts,
  meetingWithCondition,
  openFromMyActions,
  said,
} from '../actions'
import { expect, test } from '../fixtures'
import { checkPageA11y } from '../meetings'
import { chooseMemoType } from '../memo-type'
import { pageAs } from '../roles'

/**
 * The Phase 4 exit journey, the committee cycle (overview §5, M2): memo → committee → agenda item → decision → numbered
 * resolution → action → follow-up memo → verified, through the screens.
 *
 * Its legs:
 * - **E02, E03 and E06** (memo → item → resolution): `committee-escalation.spec`, where the credit memo waits for the
 *   committee, is placed on a meeting, approved and numbered, and moves on with its number.
 * - **E07** (resolution → action → follow-up memo → verified): here. The resolution comes from a meeting the company
 *   secretary prepares through the API (an item approved with a condition, not yet numbered); from then on everything is
 *   done on the screens: the condition made an action for Demo Director on the decision panel, sent when the meeting is
 *   marked as held (the resolution then takes its number), the director's follow-up memo raised from the action, the
 *   action marked as done, and the secretary's verification, which shows the memo, closing it.
 * - **E08 T8** joins the legs into one test from the memo's creation and adds the decision register's leg (the resolution
 *   found in the register and opened on to its actions and follow-up memos). Until then the legs run on their own.
 */
test.describe('the committee cycle', () => {
  test.use({ role: 'secretary' })

  // E08 T8: start here from committee-escalation.spec's memo and resolution instead of the meeting prepared below.
  test('E07 leg: resolution → action → follow-up memo → verified', async ({ page, browser }) => {
    test.setTimeout(300_000)
    const stamp = String(Date.now())
    const condition = `A first charge over the stock registered before the first drawing (${stamp}).`
    const meeting = await meetingWithCondition(browser, page, {
      title: `E2E cycle ${stamp}`,
      item: `Working capital limit, Jamuna Rice Mills ${stamp}`,
      condition,
    })

    // Resolution → action: the condition becomes an action, sent when the meeting is held; the resolution is numbered.
    await conditionAsDraftAction(page, meeting)
    await markHeldSendingDrafts(page)
    const agendaItem = page.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: meeting.item })
    let number = ''
    await expect(async () => {
      await page.reload()
      number = /MCC\/\d{4}\/\d{3}/.exec((await agendaItem.textContent()) ?? '')?.[0] ?? ''
      expect(number).not.toBe('')
    }).toPass({ timeout: 60_000 })

    // Action → follow-up memo: the director raises a General memo from the action.
    const director = await pageAs(browser, 'director')
    await openFromMyActions(director, condition)
    const actionUrl = director.url()
    await expect(director.getByText(number).first()).toBeVisible()
    const reference = ((await director.getByText(/^MCC A\d+\/\d{4} · /).textContent()) ?? '').split(' · ')[0] ?? ''
    expect(reference).toMatch(/^MCC A\d+\/\d{4}$/)
    await director.getByRole('button', { name: 'Raise a memo' }).click()
    const raise = director.getByRole('dialog', { name: `Raise a memo from ${reference}` })
    await chooseMemoType(director, 'General memo')
    await expect(raise.getByRole('textbox', { name: 'Subject' })).toHaveValue(condition)
    await checkPageA11y(director)
    await raise.getByRole('button', { name: 'Create the draft memo' }).click()
    await expect(director.getByRole('heading', { level: 1, name: condition })).toBeVisible()
    const origin = director.getByRole('link', { name: `Raised from action ${reference}` })
    await expect(origin).toBeVisible()
    await checkPageA11y(director)

    // Back to the action from the memo, marked as done.
    await origin.click()
    await expect(director).toHaveURL(actionUrl)
    await expect(director.getByRole('region', { name: 'Follow-up memos' })).toContainText(condition)
    await director.getByRole('button', { name: 'Mark as done' }).click()
    const done = director.getByRole('dialog', { name: 'Mark as done' })
    await done
      .getByRole('textbox', { name: /What was done/ })
      .fill('The charge is registered; the memo asks for the drawing.')
    await done.getByRole('button', { name: 'Mark as done' }).click()
    await expect(said(director, /^Sent to the .+ secretary to verify\./)).toBeFocused()
    await director.context().close()

    // Verified: the secretary sees the follow-up memo in the verification and closes the action.
    await page.goto(actionUrl)
    await page.getByRole('button', { name: 'Verify' }).click()
    const verify = page.getByRole('dialog', { name: 'Verify the action' })
    await expect(verify.getByText('Follow-up memos')).toBeVisible()
    await expect(verify).toContainText(condition)
    await verify.getByRole('radio', { name: 'Yes: close the action' }).check()
    await checkPageA11y(page)
    await verify.getByRole('button', { name: 'Confirm' }).click()
    await expect(said(page, 'The action is closed. The owner is told.')).toBeFocused()
    await expect(page.locator('[data-rag]').first()).toHaveText('Closed')
  })
})
