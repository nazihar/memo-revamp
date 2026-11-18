import type { MeetingResponse } from '@/api/generated/types.gen'
import type { MeetingMode } from './shared'
import { joinLocal, ORGANISATION_ZONE, splitLocal } from './time'

/** The date, times and zone as the form holds them (local to the zone; the server resolves them). */
export interface TimesValue {
  date: string
  from: string
  to: string
  otherDay: boolean
  endDate: string
  zone: string
}

/** The form's times: the meeting's own, or empty with the organisation's zone for a new meeting. */
export function timesOf(m?: Pick<MeetingResponse, 'localStartsAt' | 'localEndsAt' | 'timeZone'>): TimesValue {
  if (!m) return { date: '', from: '', to: '', otherDay: false, endDate: '', zone: ORGANISATION_ZONE }
  const start = splitLocal(m.localStartsAt)
  const end = splitLocal(m.localEndsAt)
  return {
    date: start.date,
    from: start.time,
    to: end.time,
    otherDay: end.date !== start.date,
    endDate: end.date,
    zone: m.timeZone,
  }
}

/** The local start and end and the zone, as the API takes them (no zone: the organisation's). */
export function timesBody(v: TimesValue): { startsAt: string; endsAt: string; timeZone?: string } {
  return {
    startsAt: joinLocal(v.date, v.from),
    endsAt: joinLocal(v.otherDay && v.endDate ? v.endDate : v.date, v.to),
    ...(v.zone ? { timeZone: v.zone } : {}),
  }
}

/** What the form checks before sending: a date and both times (the server checks the rest, in words). */
export function missingTimes(required: string, v: TimesValue): Record<string, string> {
  const fields: [string, string][] = [
    ['date', v.date],
    ['startsAt', v.from],
    ['endsAt', v.to],
    ...(v.otherDay ? ([['endDate', v.endDate]] as [string, string][]) : []),
  ]
  return Object.fromEntries(fields.filter(([, value]) => !value).map(([field]) => [field, required]))
}

/** The place as the form holds it. */
export interface PlaceValue {
  mode: MeetingMode
  location: string
  joinUrl: string
  joinDetails: string
}

export function placeOf(m?: Pick<MeetingResponse, 'mode' | 'location' | 'joinUrl' | 'joinDetails'>): PlaceValue {
  return {
    mode: m?.mode ?? 'IN_PERSON',
    location: m?.location ?? '',
    joinUrl: m?.joinUrl ?? '',
    joinDetails: m?.joinDetails ?? '',
  }
}

/** The place for the API: only what the mode uses, blanks left out. */
export function placeBody(v: PlaceValue): Pick<MeetingResponse, 'mode' | 'location' | 'joinUrl' | 'joinDetails'> {
  const inPerson = v.mode !== 'ONLINE'
  const online = v.mode !== 'IN_PERSON'
  return {
    mode: v.mode,
    location: inPerson && v.location.trim() ? v.location.trim() : null,
    joinUrl: online && v.joinUrl.trim() ? v.joinUrl.trim() : null,
    joinDetails: online && v.joinDetails.trim() ? v.joinDetails.trim() : null,
  }
}
