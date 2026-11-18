-- The committee term (ADR-0056, Phase 4 E01 T4): tenants may rename "committee" like the other terms (ADR-0032).
-- Expand only: the check widens, so an app version that does not know the term keeps working.
ALTER TABLE tenancy_term DROP CONSTRAINT tenancy_term_term_check;
ALTER TABLE tenancy_term ADD CONSTRAINT tenancy_term_term_check CHECK (term IN ('unit', 'position', 'memo', 'forum'));
