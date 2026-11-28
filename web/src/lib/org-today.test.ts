import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { later, todayIso } from '@/features/actions/officer/shared'
import { presetRange } from '@/features/analytics/period'
import { periodStart } from '@/features/dashboard/scope'
import { todayIso as registerToday } from '@/features/decision-register/words'
import { today as forumsToday } from '@/features/forums/shared'
import { sessionQuery, refreshSession } from '@/features/auth/session'
import { sessionHandler, server, signedInSession } from '@/test/msw'
import { isoDay, orgToday, orgTodayDate, rememberOrgToday } from './org-today'

describe('orgToday', () => {
  it("is the browser's date until a session says the organisation's", () => {
    expect(orgToday()).toBe(isoDay(new Date()))
    rememberOrgToday('2027-01-01')
    expect(orgToday()).toBe('2027-01-01')
    expect(isoDay(orgTodayDate())).toBe('2027-01-01')
    rememberOrgToday(undefined)
    expect(orgToday()).toBe(isoDay(new Date()))
  })

  it("takes the organisation's today from each session the server answers with, and forgets it on sign-out", async () => {
    // Far from the browser's date, so the test cannot pass on the fallback.
    server.use(sessionHandler({ ...signedInSession(), today: '2031-03-15' }))
    const queryClient = new QueryClient()
    await queryClient.query(sessionQuery())
    expect(orgToday()).toBe('2031-03-15')
    // Months and days count from it: the due date's quick picks, the periods.
    const next = orgTodayDate()
    next.setDate(next.getDate() + 20)
    expect(isoDay(next)).toBe('2031-04-04')

    server.use(sessionHandler())
    await refreshSession(queryClient, { state: 'ANONYMOUS', organisation: 'Bank of Example' })
    expect(orgToday()).toBe(isoDay(new Date()))
  })
})

describe("the web's date defaults", () => {
  it("start on the organisation's today", () => {
    rememberOrgToday('2031-03-15')
    expect(presetRange('thisYear')).toEqual({ from: '2031-01-01', to: '2031-03-15' })
    expect(presetRange('2y')).toEqual({ from: '2029-03-16', to: '2031-03-15' })
    expect(periodStart(4)).toBe('2031-02-16')
    expect([todayIso(), later({ days: 7 }), later({ months: 1 })]).toEqual(['2031-03-15', '2031-03-22', '2031-04-15'])
    expect(registerToday()).toBe('2031-03-15')
    expect(forumsToday()).toBe('2031-03-15')
  })
})
