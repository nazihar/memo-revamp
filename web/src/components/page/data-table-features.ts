import {
  rowSelectionFeature,
  rowSortingFeature,
  tableFeatures,
  type ColumnDef,
  type RowData,
} from '@tanstack/react-table'

/** Features of every list table (TanStack Table v9 registers features explicitly). */
export const dataTableFeatures = tableFeatures({ rowSortingFeature, rowSelectionFeature })
export type DataTableFeatures = typeof dataTableFeatures
export type DataTableColumn<T extends RowData> = ColumnDef<DataTableFeatures, T>
