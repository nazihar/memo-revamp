-- Root (cross-module) migrations run before any module (Spring Modulith, ADR-0007). Keep this folder nearly empty:
-- module tables belong in db/migration/<module>.
COMMENT ON SCHEMA memo IS 'Memo & Meeting Management. Tables are prefixed with their owning module. RLS conventions: ADR-0006.';
