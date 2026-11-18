import type { IntlShape } from 'react-intl'

/** The organisation's zone in the time-zone picker: no zone is sent, and the server uses the organisation's (it may be a
 * fixed offset). */
export const ORGANISATION_ZONE = ''

/** What the meeting time helpers need of a meeting: its instants and the IANA zone it is held in. */
export interface MeetingTimes {
  startsAt: string
  endsAt: string
  timeZone: string
}

/** The viewer's own IANA zone, as the browser reports it. */
export function viewerZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}

/** The city of an IANA zone, as people say it: `Asia/Dhaka` → "Dhaka", `America/Argentina/Buenos_Aires` → "Buenos Aires". */
export function cityOf(zone: string): string {
  return (zone.split('/').at(-1) ?? zone).replaceAll('_', ' ')
}

/** Every IANA zone the browser knows (the server checks the name against its own list: `UNKNOWN_ZONE`). */
export function allZones(): string[] {
  return typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : ['UTC', 'Asia/Dhaka']
}

/** A zone's offset now, for example "GMT+6", in [locale]. */
function offsetOf(zone: string, locale: string, at: Date): string {
  try {
    const parts = new Intl.DateTimeFormat(locale, { timeZone: zone, timeZoneName: 'shortOffset' }).formatToParts(at)
    return parts.find((p) => p.type === 'timeZoneName')?.value ?? ''
  } catch {
    return ''
  }
}

/** A zone's long name in [locale], for example "Bangladesh Standard Time" (searchable in the reader's language). */
function longNameOf(zone: string, locale: string, at: Date): string {
  try {
    const parts = new Intl.DateTimeFormat(locale, { timeZone: zone, timeZoneName: 'long' }).formatToParts(at)
    return parts.find((p) => p.type === 'timeZoneName')?.value ?? ''
  } catch {
    return ''
  }
}

/** One choice of the time-zone picker. */
export interface ZoneOption {
  zone: string
  city: string
  /** "Dhaka (GMT+6)". */
  label: string
  /** "Asia/Dhaka · Bangladesh Standard Time". */
  detail: string
  /** Lower-cased text the search matches: city, zone name and the long name. */
  search: string
}

const cache = new Map<string, ZoneOption[]>()

/** The picker's choices in [locale], by city; built once per locale. */
export function zoneOptions(locale: string): ZoneOption[] {
  const found = cache.get(locale)
  if (found) return found
  const now = new Date()
  const options = allZones()
    .map((zone) => {
      const city = cityOf(zone)
      const offset = offsetOf(zone, locale, now)
      const long = longNameOf(zone, locale, now)
      return {
        zone,
        city,
        label: offset ? `${city} (${offset})` : city,
        detail: [zone, long].filter(Boolean).join(' · '),
        search: fold(`${city} ${zone} ${long}`),
      }
    })
    .sort((a, b) => a.city.localeCompare(b.city))
  cache.set(locale, options)
  return options
}

/** Lower case without accents, so "sao paulo" finds "São Paulo". */
export function fold(text: string): string {
  return text.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase()
}

/** The choices matching [query] (every word somewhere in the city, the zone name or the long name), at most [limit]. */
export function searchZones(options: ZoneOption[], query: string, limit = 50): ZoneOption[] {
  const words = fold(query).split(/\s+/).filter(Boolean)
  const matches = words.length ? options.filter((o) => words.every((w) => o.search.includes(w))) : options
  return matches.slice(0, limit)
}

/** The calendar day of [instant] in [zone], as `YYYY-MM-DD`. */
function dayIn(instant: string, zone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(
    new Date(instant),
  )
}

/** The meeting's date and its times in [zone]: "Thu, Dec 12, 2026" and "10:00 AM – 1:00 PM" (both in the locale's way). */
export function whenIn(intl: IntlShape, m: MeetingTimes, zone: string): { date: string; times: string } {
  const sameDay = dayIn(m.startsAt, zone) === dayIn(m.endsAt, zone)
  const date = intl.formatDate(m.startsAt, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: zone,
  })
  const times = intl.formatDateTimeRange(new Date(m.startsAt), new Date(m.endsAt), {
    ...(sameDay ? {} : { day: 'numeric', month: 'short' }),
    hour: 'numeric',
    minute: '2-digit',
    timeZone: zone,
  })
  return { date, times }
}

/**
 * The meeting's times on the viewer's own clock, or undefined when that clock shows the same (flow N3: "your time"
 * appears only when the zones differ). The date is added when the viewer's day is not the meeting's.
 */
export function yourTimes(intl: IntlShape, m: MeetingTimes, zone = viewerZone()): string | undefined {
  const theirs = whenIn(intl, m, m.timeZone)
  const mine = whenIn(intl, m, zone)
  if (mine.times === theirs.times && mine.date === theirs.date) return undefined
  if (mine.date === theirs.date) return mine.times
  return intl.formatDateTimeRange(new Date(m.startsAt), new Date(m.endsAt), {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
    timeZone: zone,
  })
}

/** A local date-time from the server (`2026-12-12T10:00:00`) split for the date and time inputs. */
export function splitLocal(local: string): { date: string; time: string } {
  return { date: local.slice(0, 10), time: local.slice(11, 16) }
}

/** The date and time inputs as the local date-time the server reads on the meeting's clock. */
export function joinLocal(date: string, time: string): string {
  return `${date}T${time}:00`
}

/** Whether [m] ended more than a day ago (design §1.1: a scheduled meeting not marked as held). */
export function longPast(m: Pick<MeetingTimes, 'endsAt'>, now = Date.now()): boolean {
  return new Date(m.endsAt).getTime() < now - 24 * 60 * 60 * 1000
}

/** Whether [m] has not ended yet. */
export function notEnded(m: Pick<MeetingTimes, 'endsAt'>, now = Date.now()): boolean {
  return new Date(m.endsAt).getTime() >= now
}

/** The wall-clock fields of [instant] in [zone], read back as if they were UTC (milliseconds). */
function wallAsUtc(instant: number, zone: string): number {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
      hour: 'numeric',
      minute: 'numeric',
    })
      .formatToParts(new Date(instant))
      .map((p) => [p.type, Number(p.value)]),
  )
  return Date.UTC(parts.year ?? 1970, (parts.month ?? 1) - 1, parts.day ?? 1, parts.hour ?? 0, parts.minute ?? 0)
}

/**
 * The instant of a local date-time (`2026-12-12T10:00:00`) on [zone]'s clock, as an ISO string, for showing the times
 * on the viewer's clock while the form is filled in; undefined when it cannot be read. The server decides gaps.
 */
export function instantOf(local: string, zone: string): string | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(local)
  if (!match) return undefined
  const [, y, mo, d, h, mi] = match.map(Number)
  const wall = Date.UTC(y ?? 0, (mo ?? 1) - 1, d ?? 1, h ?? 0, mi ?? 0)
  try {
    let guess = wall - (wallAsUtc(wall, zone) - wall)
    guess = wall - (wallAsUtc(guess, zone) - guess)
    return new Date(guess).toISOString()
  } catch {
    return undefined
  }
}
