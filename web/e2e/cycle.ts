import type { Browser, Locator, Page } from '@playwright/test'
import { dhaka } from './actions'
import { confirmIfAsked, demoSecret } from './auth'
import { docx } from './docx'
import { expect } from './fixtures'
import { checkPageA11y } from './meetings'
import { chooseMemoType } from './memo-type'
import { pageAs } from './roles'

/**
 * The legs of the Phase 4 exit journey (`committee-cycle.spec`, E08 T8) as screens, each what an earlier epic's journey
 * does through the UI, made into one step the cycle can chain: the memo's committee panel (E02), the agenda builder
 * (E03), "Will you attend?" and declaring an interest (E04, E05), the register at the meeting (E05), the decision panel
 * (E06), the minutes' path by circulation (E06), the memo's route (Phase 2), the register of decisions (E08). Everything
 * here is done through the screens; nothing is prepared through the API.
 */

/** The memo's status badge. */
export function memoStatus(page: Page, text: string): Locator {
  return page.locator('[data-tone]', { hasText: text })
}

/** Opens [button]'s dialog on the memo at [memoUrl] and confirms it (with a step-up if asked), as `memo.spec` does. */
export async function memoAction(page: Page, memoUrl: string, button: string): Promise<void> {
  await page.goto(memoUrl)
  await page.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: button }).click()
  await page.getByRole('dialog').getByRole('button', { name: button }).click()
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'), page.getByText('Done.'))
  await expect(page.getByText('Done.')).toBeVisible()
}

/** Uploads a Word document as the memo's document. */
export async function uploadDocument(page: Page, text: string): Promise<void> {
  await page.getByLabel('Upload the document').setInputFiles({
    name: 'memo.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: docx(text),
  })
  await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
}

/** Names [approver] (typed [search], picked by [name]) as the memo's only approver and saves the route. */
export async function routeTo(page: Page, search: string, name: RegExp): Promise<void> {
  await page.getByLabel('Approver', { exact: true }).fill(search)
  await page.getByRole('option', { name }).click()
  await page.getByRole('button', { name: 'Add approver' }).click()
  await page.getByRole('button', { name: 'Save approvers' }).click()
  await expect(page.getByText('Approvers saved.')).toBeVisible()
}

/** The memo's committee panel. */
export function committeePanel(page: Page): Locator {
  return page.getByRole('region', { name: 'Committees' })
}

/**
 * E02 (`committee-escalation.spec`): Demo User's credit proposal of BDT [amount] for [customer], above the demo's BDT 10
 * million threshold, submitted and recommended at the credit desk by Demo Head of Credit; the route then waits for the
 * Management Credit Committee and the memo says so. [page] is the initiator's; the memo's address and number.
 */
export async function creditMemoToCommittee(
  page: Page,
  browser: Browser,
  { subject, customer, amount }: { subject: string; customer: string; amount: number },
): Promise<{ memoUrl: string; memoNumber: string }> {
  await page.goto('/memos/new')
  await chooseMemoType(page, 'Credit proposal')
  await page.getByLabel('Subject').fill(subject)
  await page.getByRole('textbox', { name: /Customer/ }).fill(customer)
  await page.getByRole('textbox', { name: /Facility amount/ }).fill(String(amount))
  await page.getByRole('radio', { name: 'Term loan' }).check()
  await page.getByRole('button', { name: 'Create draft' }).click()
  await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
  const memoUrl = page.url()
  await uploadDocument(page, `Term loan of BDT ${String(amount)} for ${customer}`)
  await memoAction(page, memoUrl, 'Submit')
  // Credit proposals are numbered on submit in their own series (a substring: the header isolates it).
  const numbered = page.getByText(/CR\/\d{4}-\d{2}\/\d{4}/).first()
  await expect(numbered).toBeVisible()
  const memoNumber = /CR\/\d{4}-\d{2}\/\d{4}/.exec((await numbered.textContent()) ?? '')?.[0] ?? ''
  expect(memoNumber).not.toBe('')

  const head = await pageAs(browser, 'headCredit')
  await memoAction(head, memoUrl, 'Recommend')
  await head.context().close()
  await page.goto(memoUrl)
  await expect(memoStatus(page, 'In review')).toBeVisible()
  await expect(page.getByText('With the Management Credit Committee', { exact: true }).first()).toBeVisible()
  await expect(committeePanel(page).getByText('Waiting for triage')).toBeVisible()
  await checkPageA11y(page)
  return { memoUrl, memoNumber }
}

/** E02 (flow M): the secretary accepts the paper [subject] from the committee's Submissions tab, by keyboard. */
export async function acceptPaper(secretary: Page, committee: string, subject: string): Promise<void> {
  await secretary.goto('/committees')
  await secretary.getByRole('link', { name: committee }).click()
  await secretary.getByRole('tab', { name: /^Submissions/ }).press('Enter')
  const triage = secretary.getByRole('button', { name: subject })
  await triage.focus()
  await triage.press('Enter')
  await secretary.getByRole('menuitem', { name: 'Accept…' }).press('Enter')
  const dialog = secretary.getByRole('dialog', { name: 'Accept the paper' })
  await expect(dialog.getByText(/The memo keeps waiting for the committee’s decision/)).toBeVisible()
  await dialog.getByRole('button', { name: 'Accept' }).press('Enter')
  await expect(secretary.getByText('Paper accepted.')).toBeVisible()
  await expect(triage).toHaveCount(0)
}

/** Whether the stack converts documents (ONLYOFFICE: nightly's documents stack and dev; not the PR smoke stack). */
export async function convertsDocuments(page: Page): Promise<boolean> {
  const editing = await page.request.get('/api/v1/files/editing')
  return editing.ok() && ((await editing.json()) as { enabled: boolean }).enabled
}

/** The agenda builder's row menu of [title]. */
function rowMenu(page: Page, title: string): Locator {
  return page.getByRole('button', { name: `Actions for ${title}` })
}

/**
 * E03 (`meeting-agenda.spec`, flows N and O): from the committee's Meetings tab, the secretary schedules [title] for now
 * (it started ten minutes ago, Dhaka time, for three hours: the invitations go out), opens the agenda builder, starts
 * the agenda from the committee's standard agenda and places the accepted paper [subject] from the tray. Placed after
 * the paper deadline, the item waits for the chair. Without a document converter the memo's Word document has no PDF for
 * members and would refuse the publication, so the secretary leaves it out of the pack (it stays with the memo). The
 * meeting's id and the builder's address.
 */
export async function scheduleNowAndPlace(
  page: Page,
  { committee, title, subject }: { committee: string; title: string; subject: string },
): Promise<{ meetingId: string; builder: string }> {
  const starts = dhaka(-10)
  const ends = dhaka(170)
  await page.goto('/committees')
  await page.getByRole('link', { name: committee }).click()
  await page.getByRole('tab', { name: 'Meetings' }).press('Enter')
  await page.getByRole('button', { name: 'Schedule a meeting' }).click()
  const sheet = page.getByRole('dialog', { name: `New meeting of ${committee}` })
  await sheet.getByRole('textbox', { name: /^Title/ }).fill(title)
  await sheet.getByLabel(/^Date/).fill(starts.slice(0, 10))
  await sheet.getByLabel(/^From/).fill(starts.slice(11, 16))
  await sheet.getByLabel(/^To/).fill(ends.slice(11, 16))
  if (ends.slice(0, 10) !== starts.slice(0, 10)) {
    await sheet.getByRole('checkbox', { name: 'Ends on another day' }).check()
    await sheet.getByLabel(/^Ends on/).fill(ends.slice(0, 10))
  }
  // The times are Dhaka's: the zone is chosen by city, whatever the organisation's (dev was on UTC before E06 T12).
  const zone = sheet.getByRole('combobox', { name: /^Time zone/ })
  await zone.fill('Dhaka')
  await sheet.getByRole('option', { name: /Dhaka/ }).first().click()
  await sheet.getByRole('radio', { name: 'In person' }).check()
  await sheet.getByRole('textbox', { name: /^Where/ }).fill('Credit Committee Room, Level 9')
  await checkPageA11y(page)
  await sheet.getByRole('button', { name: 'Schedule' }).click()
  await expect(page.getByText('Meeting scheduled. The members can see it now.')).toBeVisible()
  await page.getByRole('link', { name: title }).click()
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
  const meetingId = new URL(page.url()).pathname.split('/').pop() ?? ''
  await page.getByRole('link', { name: 'Open the agenda builder' }).click()
  const builder = page.url()

  await page.getByRole('button', { name: 'Start' }).click()
  await expect(rowMenu(page, 'Welcome and apologies')).toBeVisible()
  const place = page
    .getByRole('listitem')
    .filter({ hasText: subject })
    .getByRole('button', { name: /^Add to agenda: / })
  await place.focus()
  await place.press('Enter')
  // Placed after the paper deadline: the builder says it goes to the chair first.
  const late = page.getByRole('dialog', { name: /^Add .+ to the agenda$/ })
  await expect(late).toContainText('This goes to the chair for agreement first')
  await late.getByRole('button', { name: 'Add to agenda' }).click()
  await expect(rowMenu(page, subject)).toBeVisible()
  await expect(page.getByText(/waiting for the chair/).first()).toBeVisible()
  await checkPageA11y(page)

  if (!(await convertsDocuments(page))) {
    const menu = rowMenu(page, subject)
    await menu.focus()
    await menu.press('Enter')
    await page.getByRole('menuitem', { name: 'Papers…' }).press('Enter')
    const pack = page.getByRole('dialog').filter({ has: page.getByRole('heading', { level: 2, name: subject }) })
    // The memo's document is the item's one paper.
    await pack.getByRole('button', { name: /^Actions for the paper Paper: / }).press('Enter')
    await page.getByRole('menuitem', { name: 'Remove from the pack' }).press('Enter')
    await page
      .getByRole('alertdialog', { name: /from the pack\?$/ })
      .getByRole('button', { name: 'Remove from the pack' })
      .press('Enter')
    await expect(pack.getByRole('button', { name: 'Put back in the pack' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(pack).toBeHidden()
  }
  return { meetingId, builder }
}

/**
 * E03 (flows Q1 and Q2): the chair agrees to include the late item [subject] and agrees the agenda in the builder; the
 * secretary publishes revision 1.
 */
export async function chairAgreesAndPublish(
  page: Page,
  browser: Browser,
  { builder, subject }: { builder: string; subject: string },
): Promise<void> {
  const chair = await pageAs(browser, 'director')
  await chair.goto(builder)
  const late = chair.getByRole('region', { name: 'Late papers: your agreement is needed' })
  await expect(late).toContainText(subject)
  await late.getByRole('button', { name: 'Agree to include it' }).click()
  await expect(chair.getByText(`${subject} is agreed and will be published as a late paper.`).first()).toBeAttached()
  await chair.getByRole('button', { name: 'Agree the agenda' }).click()
  await chair
    .getByRole('alertdialog', { name: 'Agree the agenda?' })
    .getByRole('button', { name: 'Agree the agenda' })
    .click()
  await expect(chair.getByText('You agreed the agenda.').first()).toBeAttached()
  await chair.context().close()

  await page.goto(builder)
  await page.getByRole('button', { name: 'Publish revision 1' }).click()
  const publishing = page.getByRole('dialog', { name: 'Publish revision 1 of the agenda' })
  await checkPageA11y(page)
  await publishing.getByRole('button', { name: 'Publish' }).press('Enter')
  await expect(page.getByText('Revision 1 of the agenda is published.').first()).toBeAttached()
}

/** E05 (`meeting-replies.spec`, flow V): the member on [page] answers "Will you attend?" with "Yes, in person". */
export async function answerYesInPerson(page: Page, meetingId: string): Promise<void> {
  await page.goto(`/meetings/${meetingId}`)
  const card = page.getByRole('region', { name: 'Will you attend?' })
  const yes = card.getByRole('group', { name: 'Will you attend?' }).getByRole('button', { name: 'Yes, in person' })
  await yes.focus()
  await yes.press('Enter')
  await expect(card.getByRole('status')).toHaveText('Your answer is saved.')
  await expect(card.getByText(/^You said: Yes, in person/)).toBeVisible()
}

/** E04 (`declarations.spec`, flow S): the member on [page] declares [interest] on [item], leaving the room for it. */
export async function declareInterest(page: Page, meetingId: string, item: string, interest: string): Promise<void> {
  await page.goto(`/meetings/${meetingId}`)
  // "Declare an interest: {number} {item}" (names are matched as substrings).
  const declare = page
    .getByRole('button', { name: 'Declare an interest: ' })
    .and(page.getByRole('button', { name: item }))
  await declare.focus()
  await declare.press('Enter')
  const sheet = page.getByRole('dialog', { name: 'Declare an interest — ' })
  await expect(
    sheet.getByRole('radio', { name: /I will leave the room for this item\. \(recommended\)/ }),
  ).toBeChecked()
  await sheet.getByRole('textbox', { name: 'What is your interest?' }).fill(interest)
  await checkPageA11y(page)
  await sheet.getByRole('button', { name: 'Declare', exact: true }).press('Enter')
  await expect(
    page.getByText('Thank you. The secretary and the chair have been told. You will not see this item’s papers.'),
  ).toBeVisible()
  await expect(page.getByRole('heading', { level: 4, name: `${item} — You declared an interest` })).toBeVisible()
}

/** The table row whose row header is [name]. */
export function rowOf(scope: Page | Locator, name: string): Locator {
  const page = 'page' in scope ? scope.page() : scope
  return scope.getByRole('row').filter({ has: page.getByRole('rowheader', { name, exact: true }) })
}

/** Presses [target] with the keyboard. */
export async function press(target: Locator): Promise<void> {
  await target.focus()
  await target.press('Enter')
}

/** The meeting page's menu command [command] ("Start the meeting…", "Mark as held…"). */
export async function meetingCommand(page: Page, command: string): Promise<void> {
  // The meeting's own menu, in its header: "Actions for {committee} — {label}" (rows of a tab have their own).
  await press(page.getByRole('button', { name: /^Actions for .+ — / }).first())
  await page.getByRole('menuitem', { name: command }).press('Enter')
}

/** Reads [path] again until [text] shows, each visit waiting a moment for the page to load (`minutes-approval.spec`). */
export async function readUntil(page: Page, path: string, text: string | RegExp): Promise<void> {
  await expect
    .poll(
      async () => {
        await page.goto(path)
        return page
          .getByText(text)
          .first()
          .waitFor({ timeout: 5_000 })
          .then(
            () => true,
            () => false,
          )
      },
      { timeout: 60_000, intervals: [2_000] },
    )
    .toBe(true)
}

/**
 * E06 (`minutes-approval.spec`, flows AD–AG) for a committee whose minutes are approved by circulation: the secretary
 * makes the draft from the meeting page and sends it to the chair (Demo Director), who agrees it, so it is circulated;
 * [answering] says "No comments"; the secretary ends the comment period early and the chair approves the minutes with
 * their authenticator.
 */
export async function minutesByCirculation(
  page: Page,
  browser: Browser,
  { meetingId, answering }: { meetingId: string; answering: 'headFinance' | 'headCredit' },
): Promise<void> {
  const minutes = `/meetings/${meetingId}/minutes`
  await page.goto(`/meetings/${meetingId}`)
  await press(page.getByRole('region', { name: 'Minutes' }).getByRole('link', { name: 'Open the minutes' }))
  await expect(page.getByRole('heading', { name: /^Minutes — Management Credit Committee/ })).toBeVisible()
  await press(page.getByRole('button', { name: 'Make the draft' }))
  await expect(page.getByText(/^Draft ready \(version 1, /)).toBeVisible({ timeout: 60_000 })
  await expect(page.getByRole('button', { name: 'Send to the chair' })).toBeEnabled({ timeout: 60_000 })
  await checkPageA11y(page)
  await press(page.getByRole('button', { name: 'Send to the chair' }))
  await press(page.getByRole('dialog', { name: 'Send the draft to the chair?' }).getByRole('button', { name: 'Send' }))
  await expect(page.getByText(/^The draft is with Demo Director for review\.$/)).toBeVisible()

  const chair = await pageAs(browser, 'director')
  await chair.goto(minutes)
  await press(
    chair.getByRole('region', { name: /— ready for your review$/ }).getByRole('button', { name: 'Ready to circulate' }),
  )
  await expect(chair.getByText('You agreed the draft. It is now circulated for comments.')).toBeVisible()

  const member = await pageAs(browser, answering)
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

  const name = answering === 'headFinance' ? 'Demo Head of Finance' : 'Demo Head of Credit'
  await readUntil(page, minutes, `No comments: ${name}`)
  await press(page.getByRole('button', { name: 'End the comment period now…' }))
  const end = page.getByRole('dialog', { name: 'End the comment period now?' })
  await end.getByRole('textbox', { name: /Reason/ }).fill('The chair asked to approve today')
  await press(end.getByRole('button', { name: 'End the comment period' }))
  await expect(page.getByText(/^Waiting for Demo Director to approve\.$/)).toBeVisible()

  await chair.goto(minutes)
  const approve = chair.getByRole('region', { name: /^Approve the minutes of / })
  await checkPageA11y(chair)
  await press(approve.getByRole('button', { name: 'Approve the minutes' }))
  await confirmIfAsked(chair, demoSecret('MEMO_DEMO_TOTP_SECRET'), chair.getByText(/^Approved on /))
  await expect(chair.getByText(/^Approved on /)).toBeVisible()
  await chair.context().close()
}

/**
 * E08 (flow AS): the register of decisions finds [expected] (a row's title) when [query] (a resolution or memo number)
 * is searched, as the person on [page]; the row.
 */
export async function findInRegister(page: Page, query: string, expected: string): Promise<Locator> {
  await page.goto('/decisions')
  const register = page.getByRole('search', { name: 'Decisions' })
  await register.getByRole('searchbox', { name: 'Words, a resolution number or a memo number' }).fill(query)
  await register.getByRole('button', { name: 'Search' }).click()
  await expect(page.getByRole('heading', { level: 2, name: /^Showing \d+ decisions?$/ })).toBeFocused()
  const row = page.getByRole('article').filter({ has: page.getByRole('link', { name: expected }) })
  await expect(row).toHaveCount(1)
  return row
}
