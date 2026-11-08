-- Tenant lifecycle (ADR-0031): when the status last changed, and slugs the platform keeps for itself (the operator
-- console's host is admin.<base domain>). Status changes are made in the tenant's own scope (policy tenant_self), so no
-- new policy is needed; operators read the registry in platform scope (platform_read).
ALTER TABLE tenancy_tenant ADD COLUMN status_changed_at timestamptz;
ALTER TABLE tenancy_tenant ADD CONSTRAINT tenancy_tenant_slug_not_reserved
    CHECK (slug NOT IN ('admin', 'www', 'api', 'mail', 'static'));
