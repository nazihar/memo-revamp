import { expect, test } from '../fixtures'
import { checkPageA11y, committeeId, dayFromNow, MeetingApi, myId } from '../meetings'
import { pageAs } from '../roles'

/**
 * The owner's side of a committee action (Phase 4 E07 T11, flows AK and AL): the company secretary raises a direct
 * action of the Board Risk Committee for Demo Director and issues it (through the API: the secretary's screens are T10's).
 * The director finds it under My actions in the inbox, with its colour in words, opens it, says how it is going with an
 * update, and reads the update on the timeline. axe finds nothing serious on either page.
 */
interface Action {
  id: string
  version: number
  reference?: string | null
}

test.describe('as an action’s owner', () => {
  test.use({ role: 'director' })

  test('finds the action under My actions, posts an update with how it is going, and reads it on the timeline', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(180_000)
    const title = `E2E action ${String(Date.now())}`
    const director = await myId(page)

    const secretary = await pageAs(browser, 'secretary')
    const api = new MeetingApi(secretary)
    const brc = await committeeId(api, 'BRC')
    const draft = await api.send<Action>(`/forums/${brc}/actions`, {
      title,
      sourceNote: 'Chair’s instruction (end-to-end test)',
      owner: { kind: 'PERSON', userId: director },
      dueDate: dayFromNow(30),
    })
    const issued = await api.send<Action>(`/actions/${draft.id}/issue`, undefined, { version: draft.version })
    await secretary.context().close()
    const reference = issued.reference ?? ''
    expect(reference).toMatch(/^BRC A\d+\/\d{4}$/)

    await app.visit('/inbox?tab=actions')
    const row = page.getByRole('link').filter({ hasText: title })
    await expect(row).toBeVisible()
    await expect(row).toContainText(reference)
    await expect(row).toContainText(/On track|Due soon|No recent update/)
    await row.click()

    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
    await expect(page.getByText(`${reference} · Board Risk Committee`)).toBeVisible()
    await checkPageA11y(page)

    await page.getByRole('button', { name: 'Post an update' }).click()
    const dialog = page.getByRole('dialog', { name: 'Post an update' })
    await dialog.getByRole('textbox', { name: /What has happened\?/ }).fill('Draft plan shared with Treasury Ops.')
    await dialog.getByRole('group', { name: 'How is it going?' }).getByRole('radio', { name: 'At risk' }).check()
    await dialog.getByRole('button', { name: 'Post the update' }).click()

    await expect(page.getByRole('status').filter({ hasText: 'Your update was posted.' })).toBeFocused()
    await expect(page.locator('[data-rag]').first()).toHaveText('At risk')
    const timeline = page.getByRole('region', { name: 'Timeline' })
    await expect(timeline.getByText('Update from Demo Director · At risk')).toBeVisible()
    await expect(timeline.getByText('“Draft plan shared with Treasury Ops.”')).toBeVisible()
    await checkPageA11y(page)
  })
})

/**
 * The secretary's side (Phase 4 E07 T10, flows AN and AJ2): on the Board Risk Committee's Actions tab the company
 * secretary raises a direct action with "New action", names Demo Director as its owner, picks the date with a quick pick
 * and sends it at once. The page says it was sent, the tracker lists it with its colour in words, and its page offers the
 * officers' buttons. axe finds nothing serious on the tracker or the action page.
 */
test.describe('as the committee’s secretary', () => {
  test.use({ role: 'secretary' })

  test('raises a direct action from the tracker and sends it to its owner', async ({ app, page }) => {
    test.setTimeout(180_000)
    const title = `E2E secretary action ${String(Date.now())}`
    const brc = await committeeId(new MeetingApi(page), 'BRC')

    await app.visit(`/committees/${brc}?tab=actions`)
    const tracker = page.getByRole('region', { name: /— Actions$/ })
    await expect(tracker.getByRole('group', { name: 'Totals' })).toBeVisible()
    await checkPageA11y(page)

    await tracker.getByRole('button', { name: 'New action' }).click()
    const form = page.getByRole('dialog', { name: /^New action for the / })
    await form.getByRole('textbox', { name: 'What is to be done' }).fill(title)
    await form.getByRole('textbox', { name: /Where it comes from/ }).fill('Chair’s instruction (end-to-end test)')
    await form.getByRole('radio', { name: 'A person' }).check()
    await form.getByRole('combobox', { name: 'Who' }).fill('Demo Dir')
    await form.getByRole('option', { name: /Demo Director/ }).click()
    if (await form.getByRole('radio', { name: 'On a date' }).isVisible()) {
      await form.getByRole('radio', { name: 'On a date' }).check()
    }
    await form.getByRole('button', { name: 'In a month' }).click()
    await form.getByRole('button', { name: 'Save and send to the owner' }).click()

    const said = tracker.getByRole('status').filter({ hasText: /^BRC A\d+\/\d{4} was sent to its owner\.$/ })
    await expect(said).toBeFocused()
    const reference = ((await said.textContent()) ?? '').replace(' was sent to its owner.', '')
    const row = tracker.getByRole('row').filter({ hasText: title })
    await expect(row).toContainText(reference)
    await expect(row).toContainText('Demo Director')
    await expect(row.locator('[data-rag]')).toHaveText(/On track|Due soon|No recent update/)

    await row.getByRole('link', { name: reference }).click()
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
    const officer = page.getByRole('region', { name: 'For the committee’s officers' })
    await expect(officer.getByRole('button', { name: 'Change the owner' })).toBeVisible()
    await expect(officer.getByRole('button', { name: 'Cancel the action' })).toBeVisible()
    await checkPageA11y(page)
  })
})
