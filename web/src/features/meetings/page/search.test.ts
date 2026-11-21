import { describe, expect, it } from 'vitest'
import { keepTab, meetingSearch, withTab } from './search'

describe('the meeting page’s address', () => {
  it('reads the tab, the paper and the revision, and drops what it does not know', () => {
    expect(meetingSearch({ tab: 'replies', paper: 'p-1', revision: '2' })).toEqual({
      tab: 'replies',
      paper: 'p-1',
      revision: 2,
    })
    expect(meetingSearch({ tab: 'minutes', revision: '0', other: 'x' })).toEqual({})
    expect(meetingSearch({ tab: ['agenda'] })).toEqual({})
  })

  it('changes the tab and keeps the paper and the revision; opening a paper keeps the tab', () => {
    expect(withTab({ paper: 'p-1', revision: 2 }, 'declarations')).toEqual({
      paper: 'p-1',
      revision: 2,
      tab: 'declarations',
    })
    expect(withTab({ tab: 'replies', revision: 2 }, undefined)).toEqual({ revision: 2 })
    expect(keepTab({ tab: 'agenda', paper: 'p-1' })).toEqual({ tab: 'agenda' })
    expect(keepTab({ paper: 'p-1' })).toEqual({})
  })
})
