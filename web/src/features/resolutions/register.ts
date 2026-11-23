import type { IntlShape } from 'react-intl'
import type { ForumResponse, ResolutionCheckResponse } from '@/api/generated/types.gen'
import { rsmv } from './messages'

/** Who reads the register (design §4): the committee's officers, `forums.manage` and `forum.read`; members do not. */
export function readsRegister(forum: ForumResponse, holdsForumRead: boolean): boolean {
  return (
    forum.canManage ||
    holdsForumRead ||
    forum.myRole === 'CHAIR' ||
    forum.myRole === 'VICE_CHAIR' ||
    forum.myRole === 'SECRETARY'
  )
}

/** "1 to 16 are all accounted for (15 in force, 1 void)", or what is missing, given twice or outside the counter. */
export function checkWords(intl: IntlShape, c: ResolutionCheckResponse): string[] {
  if (c.lastNumber < c.firstNumber) return [intl.formatMessage(rsmv.checkEmpty, {})]
  if (c.complete) {
    return [
      intl.formatMessage(rsmv.checkComplete, {
        first: c.firstNumber,
        last: c.lastNumber,
        inForce: c.inForce,
        void: c.void,
      }),
    ]
  }
  const list = (ns: number[]) => intl.formatList(ns.map((n) => intl.formatNumber(n, { useGrouping: false })))
  return [
    c.missing.length || c.missingCount
      ? intl.formatMessage(rsmv.checkMissing, {
          numbers: list(c.missing),
          count: Math.max(c.missingCount, c.missing.length),
        })
      : undefined,
    c.duplicates.length
      ? intl.formatMessage(rsmv.checkDuplicates, { numbers: list(c.duplicates), count: c.duplicates.length })
      : undefined,
    c.outOfRange.length
      ? intl.formatMessage(rsmv.checkStray, { numbers: list(c.outOfRange), count: c.outOfRange.length })
      : undefined,
  ].filter((l): l is string => Boolean(l))
}
