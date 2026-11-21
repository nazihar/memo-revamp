import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { Tabs, TabsList, TabsTrigger } from './misc'

describe('TabsList', () => {
  it('wraps its tabs instead of widening the page at 320 px (WCAG 1.4.10)', () => {
    renderWithProviders(
      <Tabs value="a">
        <TabsList aria-label="Sections">
          <TabsTrigger value="a">A</TabsTrigger>
          <TabsTrigger value="b">B</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    expect(screen.getByRole('tablist', { name: 'Sections' })).toHaveClass('flex', 'flex-wrap')
  })
})
