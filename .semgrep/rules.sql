-- Fixtures for .semgrep/rules.yml (`semgrep --test .semgrep/`). Not run.

-- ruleid: memo-security-definer-only-in-search
CREATE FUNCTION leaks() RETURNS void LANGUAGE sql SECURITY DEFINER AS $$ SELECT 1 $$;

-- ok: memo-security-definer-only-in-search
CREATE FUNCTION keeps() RETURNS void LANGUAGE sql SECURITY INVOKER AS $$ SELECT 1 $$;

-- ok: memo-security-definer-only-in-search
-- Functions are SECURITY INVOKER (the default); SECURITY DEFINER is forbidden by the RLS meta-test.
