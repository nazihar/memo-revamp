-- Phase 3 E01 T4 (ADR-0044, design E01 §1, §7): configurable numbering. A memo-type version says how its memos are numbered;
-- numbers come from a counter row per tenant, series, period and scope, taken in the business transaction (gapless), at
-- submit or at final approval, so drafts carry no number. Existing references are kept, and today's M-YYYY-NNNNN series
-- continues as the series `general`. Expand: memo_reference_counter stays until the contract migration.

ALTER TABLE memo_type_version
    ADD COLUMN numbering jsonb NOT NULL DEFAULT '{"series":"general","parts":[{"kind":"TEXT","text":"M-"},{"kind":"YEAR","digits":4},{"kind":"TEXT","text":"-"},{"kind":"SEQUENCE","digits":5}],"reset":"YEARLY","countPer":"SERIES","moment":"ON_SUBMIT","fyStartMonth":7}'
        CHECK (jsonb_typeof(numbering) = 'object' AND octet_length(numbering::text) <= 4096);

-- Drafts are unnumbered now; a number is at most 100 characters (ADR-0044).
ALTER TABLE memo
    DROP CONSTRAINT memo_reference_check,
    ALTER COLUMN reference DROP NOT NULL,
    ADD CONSTRAINT memo_reference_length CHECK (length(reference) BETWEEN 1 AND 100),
    ADD COLUMN number_series   text   CHECK (number_series ~ '^[a-z][a-z0-9-]{0,49}$'),
    ADD COLUMN number_period   text   CHECK (number_period ~ '^(ALL|[0-9]{4}|FY[0-9]{4}-[0-9]{2})$'),
    ADD COLUMN number_scope    text   CHECK (length(number_scope) <= 100),
    ADD COLUMN number_sequence bigint CHECK (number_sequence >= 1),
    ADD COLUMN numbered_at     timestamptz,
    ADD CONSTRAINT memo_number_parts CHECK (
        (number_series IS NULL) = (number_period IS NULL)
        AND (number_series IS NULL) = (number_scope IS NULL)
        AND (number_series IS NULL) = (number_sequence IS NULL)
        AND (number_series IS NULL OR reference IS NOT NULL)
    );
-- The register: numbers of a series, period and scope in order; at most one memo per number.
CREATE UNIQUE INDEX memo_number_uk ON memo (tenant_id, number_series, number_period, number_scope, number_sequence)
    WHERE number_series IS NOT NULL;

CREATE TABLE memo_number_counter (
    tenant_id        uuid    NOT NULL,
    series           text    NOT NULL CHECK (series ~ '^[a-z][a-z0-9-]{0,49}$'),
    period           text    NOT NULL CHECK (period ~ '^(ALL|[0-9]{4}|FY[0-9]{4}-[0-9]{2})$'),
    scope            text    NOT NULL CHECK (length(scope) <= 100),
    last_number      bigint  NOT NULL CHECK (last_number >= 0),
    -- Set when an administrator chose where the series starts (to continue a legacy one, ADR-0044).
    started_by_admin boolean NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, series, period, scope)
);
ALTER TABLE memo_number_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE memo_number_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memo_number_counter FOR ALL
    USING      (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (tenant_id = (select nullif(current_setting('app.tenant_id', true), '')::uuid));

-- A number, once given, never changes (threat model T3).
CREATE FUNCTION memo_number_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
AS $$
BEGIN
    IF OLD.reference IS NOT NULL AND (
        NEW.reference IS DISTINCT FROM OLD.reference
        OR NEW.number_series IS DISTINCT FROM OLD.number_series
        OR NEW.number_period IS DISTINCT FROM OLD.number_period
        OR NEW.number_scope IS DISTINCT FROM OLD.number_scope
        OR NEW.number_sequence IS DISTINCT FROM OLD.number_sequence
    ) AND NOT (OLD.number_series IS NULL AND NEW.reference = OLD.reference) THEN
        RAISE EXCEPTION 'memo % is numbered; its number cannot change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION memo_number_guard() FROM PUBLIC, memo_app;
CREATE TRIGGER memo_number_guard BEFORE UPDATE ON memo FOR EACH ROW EXECUTE FUNCTION memo_number_guard();

-- Backfill across tenants: FORCE binds the owner too and no tenant is set here, so lift it for this transaction.
ALTER TABLE memo NO FORCE ROW LEVEL SECURITY;
ALTER TABLE memo_number_counter NO FORCE ROW LEVEL SECURITY;
ALTER TABLE memo_reference_counter NO FORCE ROW LEVEL SECURITY;

-- Existing references are numbers of the series `general`: year and sequence read from M-YYYY-NNNNN.
UPDATE memo
SET number_series   = 'general',
    number_period   = substring(reference FROM 3 FOR 4),
    number_scope    = '',
    number_sequence = substring(reference FROM 8)::bigint,
    numbered_at     = created_at
WHERE reference ~ '^M-[0-9]{4}-[0-9]{5,}$';

INSERT INTO memo_number_counter (tenant_id, series, period, scope, last_number)
SELECT tenant_id, 'general', year::text, '', last_number FROM memo_reference_counter;

ALTER TABLE memo FORCE ROW LEVEL SECURITY;
ALTER TABLE memo_number_counter FORCE ROW LEVEL SECURITY;
ALTER TABLE memo_reference_counter FORCE ROW LEVEL SECURITY;
