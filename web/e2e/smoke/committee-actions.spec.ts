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
