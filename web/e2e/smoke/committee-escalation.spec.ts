import type { Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { committeeId, MeetingApi, myId, type Draft } from '../meetings'
import { chooseMemoType } from '../memo-type'
import { pageAs } from '../roles'

/**
 * The E02 journey (test plan T5, flow M): a credit proposal of BDT 12 million, above the demo's committee threshold of
 * BDT 10 million, goes to the credit desk; after the head of credit's recommendation the route waits for the Management
 * Credit Committee (ADR-0058) and the memo says so. The company secretary accepts the paper from the committee's
 * Submissions tab with the keyboard, and the initiator sees it accepted on the memo's committee panel.
 *
 * The exit journey's E06 leg (T12): the secretary places the paper on a meeting held now (prepared through the API; the
 * chair's agreement to the late item recorded on their behalf), records "Approved" in the Decisions tab and marks the
 * meeting as held; the resolution is numbered, and the memo moves on with it: the initiator reads the number on the
 * committee panel and on the note sheet, and the route goes on to the authority.
 */
interface Meeting {
  id: string
  version: number
}

/** A local date-time [minutes] from now in Asia/Dhaka (UTC+06:00 all year), as the meetings API takes it. */
function dhaka(minutes: number): string {
  return new Date(Date.now() + (6 * 60 + minutes) * 60_000).toISOString().slice(0, 16) + ':00'
}
function status(page: Page, text: string) {
  return page.locator('[data-tone]', { hasText: text })
}

function toolbar(page: Page) {
  return page.getByRole('toolbar', { name: 'Actions' })
}

/** Opens [button]'s dialog on the memo, confirms it (with a step-up if asked). */
async function act(page: Page, memoUrl: string, button: string) {
  await page.goto(memoUrl)
  await toolbar(page).getByRole('button', { name: button }).click()
  await page.getByRole('dialog').getByRole('button', { name: button }).click()
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'), page.getByText('Done.'))
  await expect(page.getByText('Done.')).toBeVisible()
}

/** A calendar day [days] from today as `YYYY-MM-DD`. */
function dayFromNow(days: number): string {
  const d = new Date(Date.now() + days * 86_400_000)
  return `${String(d.getFullYear())}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('a large credit proposal waits for the credit committee, whose secretary accepts it', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(300_000)
    const subject = `E2E committee ${String(Date.now())}`

    // A credit proposal above the committee threshold.
    await app.visit('/memos/new')
    await chooseMemoType(page, 'Credit proposal')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('textbox', { name: /Customer/ }).fill('Delta Textiles Ltd')
    await page.getByRole('textbox', { name: /Facility amount/ }).fill('12000000')
    await page.getByRole('radio', { name: 'Term loan' }).check()
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()
    await page.getByLabel('Upload the document').setInputFiles({
      name: 'proposal.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('Term loan of BDT 12 million'),
    })
    await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
    await act(page, memoUrl, 'Submit')

    // The credit desk recommends; the route then waits for the committee instead of going to the authority.
    const head = await pageAs(browser, 'headCredit')
    await act(head, memoUrl, 'Recommend')
    await page.goto(memoUrl)
    await expect(status(page, 'In review')).toBeVisible()
    await expect(page.getByText('With the Management Credit Committee', { exact: true }).first()).toBeVisible()
    const panel = page.getByRole('region', { name: 'Committees' })
    await expect(panel.getByText('Waiting for triage')).toBeVisible()
    await expect(panel.getByText('Sent to the Management Credit Committee', { exact: false })).toBeVisible()
    await app.checkA11y()

    // The secretary opens the committee's papers and accepts this one for a meeting, by keyboard.
    const secretary = await pageAs(browser, 'secretary')
    await secretary.goto('/committees')
    await secretary.getByRole('link', { name: 'Management Credit Committee' }).click()
    await secretary.getByRole('tab', { name: /^Submissions/ }).press('Enter')
    // The Triage menu button is named after the paper: "Triage CR/… <subject>" (a substring match).
    const triage = secretary.getByRole('button', { name: subject })
    await triage.focus()
    await triage.press('Enter')
    await secretary.getByRole('menuitem', { name: 'Accept…' }).press('Enter')
    const dialog = secretary.getByRole('dialog', { name: 'Accept the paper' })
    await expect(dialog.getByText(/The memo keeps waiting for the committee’s decision/)).toBeVisible()
    await dialog.getByLabel(/Target meeting date/).fill(dayFromNow(28))
    await dialog.getByRole('button', { name: 'Accept' }).press('Enter')
    await expect(secretary.getByText('Paper accepted.')).toBeVisible()
    await expect(triage).toHaveCount(0)

    // The initiator sees where the paper stands.
    await page.goto(memoUrl)
    await expect(panel.getByText('Accepted', { exact: true })).toBeVisible()
    await expect(panel.getByText(/Accepted for the meeting of/)).toBeVisible()
    await expect(panel.getByText('by Demo Company Secretary')).toBeVisible()
    await expect(panel.getByText('Next: decided at a meeting')).toBeVisible()
    await expect(status(page, 'In review')).toBeVisible()
    await app.checkA11y()

    // E06: a meeting held now with the paper on its agenda (the chair's agreement to the late item recorded for them).
    const api = new MeetingApi(secretary)
    const mcc = await committeeId(api, 'MCC')
    const paper = (
      await api.get<{ id: string; memo?: { subject?: string } | null }[]>(`/forums/${mcc}/submissions`)
    ).find((s) => s.memo?.subject === subject)
    if (!paper) throw new Error('the paper is not in the committee’s queue')
    const meeting = await api.send<Meeting>(`/forums/${mcc}/meetings`, {
      kind: 'ORDINARY',
      title: `E2E escalation ${String(Date.now())}`,
      startsAt: dhaka(-20),
      endsAt: dhaka(100),
      timeZone: 'Asia/Dhaka',
      mode: 'IN_PERSON',
      location: 'Credit Committee Room, Level 9',
    })
    let draft = await api.send<Draft>(`/meetings/${meeting.id}/agenda`, { from: 'TEMPLATE' })
    draft = await api.send<Draft>(
      `/meetings/${meeting.id}/agenda/items`,
      { kind: 'SUBMISSION', submissionId: paper.id, minutes: 20 },
      { version: draft.version },
    )
    const placed = draft.sections.flatMap((s) => s.items).find((i) => i.title === subject) as
      { id: string; late?: string; papers?: { id: string }[] } | undefined
    if (!placed) throw new Error('the paper is not on the agenda')
    // The smoke stack has no document converter, so the memo's Word document has no PDF for members and would refuse
    // the publication (PAPER_NOT_READABLE): the secretary leaves it out of the pack, as in meeting-agenda.spec.
    for (const p of placed.papers ?? []) {
      draft = await api.send<Draft>(
        `/meetings/${meeting.id}/agenda/papers/${p.id}/remove`,
        {},
        { version: draft.version },
      )
    }
    // Placed after the paper deadline, the item waits for the chair, who agreed by phone.
    if (placed.late === 'AWAITING_CHAIR') {
      draft = await api.send<Draft>(
        `/meetings/${meeting.id}/agenda/items/${placed.id}/late-decision`,
        { agree: true, onBehalf: true, note: 'Agreed by the chair by phone' },
        { version: draft.version },
      )
    }
    draft = await api.send<Draft>(
      `/meetings/${meeting.id}/agenda/chair-agreement`,
      { onBehalf: true, note: 'In person' },
      { version: draft.version },
    )
    let current = await api.get<Meeting>(`/meetings/${meeting.id}`)
    await api.send(`/meetings/${meeting.id}/schedule`, {}, { version: current.version })
    await api.send<Draft>(`/meetings/${meeting.id}/agenda/publish`, {}, { version: draft.version })
    const members: string[] = []
    for (const role of ['director', 'headCredit', 'headFinance'] as const) {
      const member = await pageAs(browser, role)
      members.push(await myId(member))
      await member.context().close()
    }
    current = await api.get<Meeting>(`/meetings/${meeting.id}`)
    await api.send(`/meetings/${meeting.id}/start`, {}, { version: current.version })
    await api.send(`/meetings/${meeting.id}/attendance/arrivals`, { userIds: members, mode: 'IN_PERSON' })

    // The secretary records the committee's decision and marks the meeting as held.
    await secretary.goto(`/meetings/${meeting.id}`)
    await secretary.getByRole('tab', { name: 'Decisions' }).press('Enter')
    const item = secretary.getByRole('navigation', { name: 'Agenda' }).getByRole('button', { name: subject })
    await item.press('Enter')
    const decision = secretary.getByRole('region', { name: ` — ${subject}` })
    await decision.getByRole('radio', { name: 'Approved', exact: true }).check()
    await decision
      .getByLabel(/^Resolution \(as it will be minuted\)/)
      .fill('It was RESOLVED that the term loan of BDT 12 million be approved.')
    await decision.getByRole('button', { name: 'Record the decision' }).press('Enter')
    await expect(secretary.getByText('The decision is recorded.')).toBeVisible()
    await secretary.getByRole('button', { name: /^Actions for / }).press('Enter')
    await secretary.getByRole('menuitem', { name: 'Mark as held…' }).press('Enter')
    const held = secretary.getByRole('dialog', { name: /as held\?$/ })
    await expect(held).toContainText('1 memo will move on now')
    await held.getByRole('button', { name: 'Mark as held' }).press('Enter')
    let number = ''
    await expect(async () => {
      await secretary.reload()
      const line = (await item.textContent()) ?? ''
      number = /MCC\/\d{4}\/\d{3}/.exec(line)?.[0] ?? ''
      expect(number).not.toBe('')
    }).toPass({ timeout: 60_000 })

    // The memo moved on with its number: the committee panel and the note sheet say so; the authority decides next.
    await page.goto(memoUrl)
    await expect(panel.getByText(` — Approved — ${number}`, { exact: false })).toBeVisible()
    await expect(page.getByText(`Resolution ${number}`, { exact: false }).first()).toBeVisible()
    await expect(page.getByText('With the Management Credit Committee', { exact: true })).toHaveCount(0)
    await app.checkA11y()
  })
})
