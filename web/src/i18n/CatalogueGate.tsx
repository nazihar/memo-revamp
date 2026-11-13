import type { ReactNode } from 'react'
import { LoadingState } from '@/components/page/States'
import type { CataloguePart } from './catalogue-parts'
import { useCatalogueParts } from './LocaleProvider'

/** Shows [children] once [parts] of the catalogue have loaded, so a page never flashes in the source language. */
export function CatalogueGate({ parts, children }: { parts: readonly CataloguePart[]; children: ReactNode }) {
  return useCatalogueParts(['core', ...parts]) ? children : <LoadingState />
}
