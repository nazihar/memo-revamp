import { openFromMyActions, resolutionNumber, said } from '../actions'
import {
  acceptPaper,
  answerYesInPerson,
  chairAgreesAndPublish,
  committeePanel,
  creditMemoToCommittee,
  declareInterest,
  findInRegister,
  meetingCommand,
  memoAction,
  minutesByCirculation,
  press,
  routeTo,
  rowOf,
  scheduleNowAndPlace,
  uploadDocument,
} from '../cycle'
import { expect, test } from '../fixtures'
import { checkPageA11y } from '../meetings'
import { chooseMemoType } from '../memo-type'
import { pageAs } from '../roles'

/**
 * The Phase 4 exit journey, the committee cycle (overview §5, M2; E08 T8), through the screens only, from the memo's
 * creation to the register's export. Each leg is an earlier epic's journey made a step (`e2e/cycle.ts`, `e2e/actions.ts`):
 *  1. **E02** A credit memo of BDT 25 million, above the BDT 10 million limit, is recommended at the credit desk and waits
 *     for the Management Credit Committee.
 *  2. **E02** The company secretary accepts it from the committee's Submissions tab.
 *  3. **E03** The secretary schedules a meeting for now, builds its agenda from the standard agenda with the paper from the
 *     tray (late, so the chair agrees to it), the chair agrees the agenda, and revision 1 is published.
 *  4. **E05** The invitations went out when it was scheduled: Demo Director, Demo Head of Credit and Demo Head of Finance
 *     answer "Yes, in person"; the secretary reads the answers on the Invitations and replies tab.
 *  5. **E04** Demo Head of Credit (the vice-chair, who recommended the memo at the desk) declares an interest in the item.
 *  6. **E05** The secretary starts the meeting, marks everyone who said yes as present and the Chief Risk Officer arriving
 *     (quorate, 4 of 4), moves to the item, and records the vice-chair stepping out for it.
 *  7. **E06** The committee approves it with a condition by 2–1, Demo Head of Finance's dissent recorded with a reason.
 *  8. **E06** Marked as held: the resolution is numbered.
 *  9. **E02/E06** The memo moves on with its number: the initiator reads it on the committee panel and the note sheet, and
 *     the route goes on to the authority.
 * 10. **E06** The minutes are approved by circulation (the committee type's way): drafted, agreed by the chair, "No
 *     comments" from the head of finance, the comment period ended early, approved by the chair with their authenticator.
 * 11. **E07** The secretary makes the condition an action owned by the position Chief Financial Officer and sends it.
 * 12. **E07** Its holder, Demo Head of Finance, raises a General memo from it, routed to Demo Director, who approves it.
 * 13. **E07** The owner marks the action done and the secretary verifies and closes it.
 * 14. **E08** The register finds the resolution by its number and by the memo's number, with the links to the meeting and
 *     the memo; the demo auditor, the committees' oversight (`forum.read`), reads the same row in full.
 * 15. **E08** The secretary exports the committee's register as a spreadsheet.
 * No document conversion is needed: on a stack without one (the PR smoke stack) the memo's Word document is left out of
 * the agenda's pack. axe finds nothing serious on the way.
 */
test.describe('the committee cycle', () => {
  test.use({ role: 'secretary', actionTimeout: 30_000 })

  test('memo → committee → agenda → meeting → resolution → memo moves on → minutes → action → follow-up memo → register', async ({
    page,
    browser,
  }) => {
    test.setTimeout(900_000)
    const stamp = String(Date.now())
    const customer = `Jamuna Rice Mills ${stamp}`
    const subject = `Term loan, ${customer}`
    const title = `E2E committee cycle ${stamp}`
    const condition = `A first charge over the stock and the mill registered before the first drawing (${stamp}).`
    const committee = 'Management Credit Committee'

    // 1. The credit memo waits for the committee.
    const initiator = await pageAs(browser, 'user')
    const { memoUrl, memoNumber } = await creditMemoToCommittee(initiator, browser, {
      subject,
      customer,
      amount: 25_000_000,
    })

    // 2. The secretary accepts it.
    await acceptPaper(page, committee, subject)
    await initiator.goto(memoUrl)
    await expect(committeePanel(initiator).getByText('Accepted', { exact: true })).toBeVisible()

    // 3. The agenda: scheduled for now, the paper placed from the tray, agreed by the chair, published.
    const { meetingId, builder } = await scheduleNowAndPlace(page, { committee, title, subject })
    await chairAgreesAndPublish(page, browser, { builder, subject })

    // 4. Invitations and replies.
    for (const role of ['director', 'headCredit', 'headFinance'] as const) {
      const member = await pageAs(browser, role)
      await answerYesInPerson(member, meetingId)
      // 5. The vice-chair declares an interest in the item and will leave the room for it.
      if (role === 'headCredit') {
        await declareInterest(member, meetingId, subject, 'My brother is a director of the borrower’s parent company.')
      }
      await member.context().close()
    }
    await page.goto(`/meetings/${meetingId}`)
    await page.getByRole('tab', { name: 'Invitations and replies' }).click()
    const replies = page.getByRole('table', { name: 'Invitations and replies' })
    for (const name of ['Demo Director', 'Demo Head of Credit', 'Demo Head of Finance']) {
      await expect(rowOf(replies, name)).toContainText('Yes, in person')
    }
    await checkPageA11y(page)

    // 6. Attendance and quorum: started, everyone who said yes present, the CRO arriving; the item in progress.
    await meetingCommand(page, 'Start the meeting…')
    await press(page.getByRole('dialog').getByRole('button', { name: 'Start the meeting' }))
    await expect(page.getByRole('tab', { name: 'Attendance' })).toHaveAttribute('aria-selected', 'true')
    const banner = page.getByRole('region', { name: 'Quorum' })
    await press(page.getByRole('button', { name: 'Mark everyone who said yes as present (3)' }))
    await press(
      page
        .getByRole('dialog', { name: 'Mark everyone who said yes as present' })
        .getByRole('button', { name: 'Mark 3 people present' }),
    )
    await expect(page.getByText('3 people are marked present.')).toBeVisible()
    const members = page.getByRole('table', { name: 'Members' })
    // The secretary and the memo's presenter are on the register too; the one member left is the Chief Risk Officer's
    // seat holder (whose name the demo's staff seating gives).
    await press(members.getByRole('button', { name: 'In person: Demo Company Secretary' }))
    await expect(page.getByText('Demo Company Secretary is present in person.')).toBeVisible()
    const cro = members
      .getByRole('row')
      .filter({ has: page.getByRole('cell', { name: 'Member', exact: true }) })
      .getByRole('button', { name: /^In person: / })
    await expect(cro).toHaveCount(1)
    await press(cro)
    await expect(banner).toContainText('Quorate — meeting: 4 of 4 present, 3 needed.')
    await press(page.getByRole('button', { name: 'Change the item' }))
    await page.getByRole('menuitemradio', { name: subject }).press('Enter')
    await expect(
      page
        .getByText(/^Now: item \d+ /)
        .filter({ hasText: subject })
        .first(),
    ).toBeVisible()
    const vice = rowOf(page, 'Demo Head of Credit')
    await expect(vice).toContainText('Declared an interest in this item')
    await press(vice.getByRole('button', { name: 'Stepped out for this item: Demo Head of Credit' }))
    await expect(page.getByText('Demo Head of Credit stepped out for this item.')).toBeVisible()
    await checkPageA11y(page)

    // 7. The decision: approved with a condition by 2–1, the head of finance's dissent recorded.
    await press(page.getByRole('tab', { name: 'Decisions' }))
    const agendaItem = page.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: subject })
    await press(agendaItem)
    const decision = page.getByRole('region', { name: ` — ${subject}` })
    await expect(decision).toContainText(/Quorate for Item \d+/)
    await decision.getByRole('radio', { name: 'Approved with conditions' }).check()
    await decision
      .getByLabel(/^Resolution \(as it will be minuted\)/)
      .fill('It was RESOLVED that the term loan of BDT 25 million be approved, on the condition below.')
    await decision.getByRole('textbox', { name: 'Condition 1' }).fill(condition)
    await press(decision.getByRole('button', { name: /^Record a vote/ }))
    await decision.getByRole('textbox', { name: 'For', exact: true }).fill('2')
    await decision.getByRole('textbox', { name: 'Against', exact: true }).fill('1')
    await press(decision.getByRole('button', { name: /^Dissent and abstentions/ }))
    await decision
      .getByRole('group', { name: 'Demo Head of Finance' })
      .getByRole('radio', { name: 'Dissented' })
      .check()
    await decision.getByLabel('Reason they asked to be recorded (optional)').fill('The mill’s leverage has risen.')
    await checkPageA11y(page)
    await press(decision.getByRole('button', { name: 'Record the decision' }))
    await expect(page.getByText('The decision is recorded.')).toBeVisible()
    await expect(agendaItem).toContainText('Approved with conditions — numbered when the meeting ends')
    await expect(decision).toContainText(
      'Demo Head of Finance asked that their dissent be recorded: The mill’s leverage has risen.',
    )

    // The vice-chair comes back once the item is decided.
    await page.getByRole('tab', { name: 'Attendance' }).click()
    await press(vice.getByRole('button', { name: 'Back: Demo Head of Credit' }))
    await expect(page.getByText('Demo Head of Credit is back in the room.')).toBeVisible()

    // 8. Marked as held: the resolution is numbered.
    await meetingCommand(page, 'Mark as held…')
    const held = page.getByRole('dialog', { name: /as held\?$/ })
    await expect(held).toContainText('1 memo will move on now')
    await press(held.getByRole('button', { name: 'Mark as held' }))
    await expect(held).toBeHidden()
    await page.getByRole('tab', { name: 'Decisions' }).click()
    const number = await resolutionNumber(page, subject)
    expect(number).toMatch(/^MCC\/\d{4}\/\d{3}$/)

    // 9. The memo moves on with its number.
    await initiator.goto(memoUrl)
    await expect(
      committeePanel(initiator).getByText(` — Approved with conditions — ${number}`, { exact: false }),
    ).toBeVisible()
    await expect(initiator.getByText(`Resolution ${number}`, { exact: false }).first()).toBeVisible()
    await expect(initiator.getByText('With the Management Credit Committee', { exact: true })).toHaveCount(0)
    await checkPageA11y(initiator)
    await initiator.context().close()

    // 10. The minutes, approved by circulation.
    await minutesByCirculation(page, browser, { meetingId, answering: 'headFinance' })

    // 11. The condition becomes an action owned by the position Chief Financial Officer, sent to its holder.
    await page.goto(`/meetings/${meetingId}`)
    await press(page.getByRole('tab', { name: 'Decisions' }))
    await press(page.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: subject }))
    await page
      .getByRole('region', { name: ` — ${subject}` })
      .getByRole('button', { name: 'Make condition 1 an action' })
      .click()
    const form = page.getByRole('dialog', { name: /^New action — from .+, condition 1$/ })
    await expect(form.getByRole('textbox', { name: 'What is to be done' })).toHaveValue(condition)
    await form.getByRole('radio', { name: 'A position' }).check()
    await form.getByRole('combobox', { name: 'Who' }).fill('Chief Financial')
    await form.getByRole('option', { name: /Chief Financial Officer/ }).click()
    await expect(form).toContainText('Now: ')
    if (await form.getByRole('radio', { name: 'On a date' }).isVisible()) {
      await form.getByRole('radio', { name: 'On a date' }).check()
    }
    await form.getByRole('button', { name: 'In a month' }).click()
    await checkPageA11y(page)
    await form.getByRole('button', { name: 'Save and send to the owner' }).click()
    await expect(page.getByText(/^MCC A\d+\/\d{4} was sent to its owner\.$/).first()).toBeVisible()

    // 12. The holder raises a follow-up memo from it, routed to Demo Director, who approves it.
    const owner = await pageAs(browser, 'headFinance')
    await openFromMyActions(owner, condition)
    const actionUrl = owner.url()
    await expect(owner.getByText(number).first()).toBeVisible()
    await expect(owner.getByText(/Chief Financial Officer/).first()).toBeVisible()
    const reference = ((await owner.getByText(/^MCC A\d+\/\d{4} · /).textContent()) ?? '').split(' · ')[0] ?? ''
    expect(reference).toMatch(/^MCC A\d+\/\d{4}$/)
    await owner.getByRole('button', { name: 'Raise a memo' }).click()
    const raise = owner.getByRole('dialog', { name: `Raise a memo from ${reference}` })
    await chooseMemoType(owner, 'General memo')
    await expect(raise.getByRole('textbox', { name: 'Subject' })).toHaveValue(condition)
    await raise.getByRole('button', { name: 'Create the draft memo' }).click()
    await expect(owner.getByRole('heading', { level: 1, name: condition })).toBeVisible()
    // The memo has the action's title: wait for its page, not the action's heading.
    await expect(owner).toHaveURL(/\/memos\//)
    const followUpUrl = owner.url()
    await expect(owner.getByRole('link', { name: `Raised from action ${reference}` })).toBeVisible()
    await uploadDocument(owner, 'The charge over the stock and the mill is registered; the drawing may proceed.')
    await routeTo(owner, 'Director', /Demo Director/)
    await memoAction(owner, followUpUrl, 'Submit')
    const approver = await pageAs(browser, 'director')
    await memoAction(approver, followUpUrl, 'Approve')
    await approver.context().close()

    // 13. Done, then verified and closed by the secretary.
    await owner.goto(followUpUrl)
    await owner.getByRole('link', { name: `Raised from action ${reference}` }).click()
    await expect(owner).toHaveURL(actionUrl)
    await expect(owner.getByRole('region', { name: 'Follow-up memos' })).toContainText(condition)
    await owner.getByRole('button', { name: 'Mark as done' }).click()
    const done = owner.getByRole('dialog', { name: 'Mark as done' })
    await done
      .getByRole('textbox', { name: /What was done/ })
      .fill('The charge is registered; the follow-up memo is approved.')
    await done.getByRole('button', { name: 'Mark as done' }).click()
    await expect(said(owner, /^Sent to the .+ secretary to verify\./)).toBeFocused()
    await owner.context().close()

    await page.goto(actionUrl)
    await page.getByRole('button', { name: 'Verify' }).click()
    const verify = page.getByRole('dialog', { name: 'Verify the action' })
    await expect(verify.getByText('Follow-up memos')).toBeVisible()
    await expect(verify).toContainText('Approved')
    await verify.getByRole('radio', { name: 'Yes: close the action' }).check()
    await checkPageA11y(page)
    await verify.getByRole('button', { name: 'Confirm' }).click()
    await expect(said(page, 'The action is closed. The owner is told.')).toBeFocused()
    await expect(page.locator('[data-rag]').first()).toHaveText('Closed')

    // 14. The register finds the resolution by its number and by the memo's number.
    const byNumber = await findInRegister(page, number, subject)
    await expect(byNumber).toContainText(`Resolution ${number}`)
    await expect(byNumber).toContainText('Approved with conditions')
    await expect(byNumber.getByRole('link', { name: `Memo ${memoNumber}` })).toBeVisible()
    await expect(byNumber.getByRole('link', { name: /^Meeting MCC \d+\/\d{4}$/ })).toBeVisible()
    await checkPageA11y(page)
    const byMemo = await findInRegister(page, memoNumber, subject)
    await expect(byMemo).toContainText(`Resolution ${number}`)
    // Oversight: the demo auditor (the "Committee oversight" role, forum.read) reads the same row in full.
    const oversight = await pageAs(browser, 'auditor')
    const seen = await findInRegister(oversight, number, subject)
    await expect(seen).toContainText('Approved with conditions')
    await checkPageA11y(oversight)
    await oversight.context().close()

    // 15. Export: the committee's register as a spreadsheet.
    const register = page.getByRole('search', { name: 'Decisions' })
    await register.getByRole('searchbox', { name: 'Words, a resolution number or a memo number' }).fill('')
    await register.getByRole('combobox', { name: 'Committee' }).selectOption({ label: committee })
    await register.getByRole('button', { name: 'Search' }).click()
    await expect(
      page.getByRole('heading', { level: 2, name: /^Showing (more than )?[\d,]+ decisions?$/ }),
    ).toBeFocused()
    await page.getByRole('button', { name: 'Export…' }).click()
    const exporting = page.getByRole('dialog', { name: 'Export decisions' })
    await expect(exporting.getByRole('radio', { name: 'Spreadsheet (.xlsx)' })).toBeChecked()
    await checkPageA11y(page)
    const download = page.waitForEvent('download')
    await exporting.getByRole('button', { name: 'Download' }).click()
    expect((await download).suggestedFilename()).toMatch(/\.xlsx$/)
    await expect(page.getByText('The register has been downloaded.')).toBeVisible()
  })
})
