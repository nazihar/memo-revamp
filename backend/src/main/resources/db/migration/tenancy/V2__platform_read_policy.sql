-- Platform scope (ADR-0017): platform jobs may list tenants (registry rows only, read only). No other table accepts
-- app.scope, so platform scope never sees tenant data. Set only by PlatformTransactions, transaction-locally.
CREATE POLICY platform_read ON tenancy_tenant FOR SELECT
    USING ((select current_setting('app.scope', true)) = 'platform');
