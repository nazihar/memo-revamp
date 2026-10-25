-- Rate-limit buckets (ADR-0025), Bucket4j's PostgreSQL layout. Platform table: the key is the first 8 bytes of
-- SHA-256("<limit>:<subject>"), so no IP address or email is stored; the state is Bucket4j's binary bucket state.
CREATE TABLE identity_rate_limit_bucket (
    id         bigint PRIMARY KEY,
    state      bytea,
    expires_at bigint
);
CREATE INDEX identity_rate_limit_bucket_expiry_idx ON identity_rate_limit_bucket (expires_at);
