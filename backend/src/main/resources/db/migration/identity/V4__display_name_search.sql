-- People picker (E04 research R14): prefix search on display names needs an operator class that supports LIKE
-- 'abc%' regardless of the database collation.
CREATE INDEX identity_user_tenant_name_search_idx ON identity_user (tenant_id, lower(display_name) text_pattern_ops);
