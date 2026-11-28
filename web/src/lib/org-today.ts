/**
 * The organisation's date today, as `2026-11-28`. The server checks and defaults dates on the organisation's calendar
 * (its time zone); the browser's calendar can be a day apart from it around midnight. The session (`GET /auth/session`,
 * read before every signed-in page and again when it is 30 seconds old) carries the organisation's `today`; until it
 * has been read, or for a session without one, this is the browser's date.
 */
let known: string | undefined

/** Keeps the organisation's [today] from the session the server answered with. */
export function rememberOrgToday(today: string | null | undefined): void {
  known = today ?? undefined
}

/** Today on the organisation's calendar: the default for new dates, the earliest a date may be, and file names. */
export function orgToday(): string {
  return known ?? browserToday()
}

/** [orgToday] as a local `Date` at midnight, for date arithmetic (add days or months, then format with [isoDay]). */
export function orgTodayDate(): Date {
  const [y, m, d] = orgToday().split('-').map(Number)
  return new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1)
}

/** A local `Date`'s calendar day as `YYYY-MM-DD`. */
export function isoDay(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${String(d.getFullYear())}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function browserToday(): string {
  return isoDay(new Date())
}
