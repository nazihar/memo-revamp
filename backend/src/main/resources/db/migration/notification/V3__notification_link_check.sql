-- The link check of V2 used `{0,499}`, beyond PostgreSQL's limit of 255 repetitions in a regular expression, so every
-- notification with a link failed with "invalid repetition count(s)". No producer sent a link before Phase 2 E01, which
-- found it. Same rule, written within the limit: an app route of at most 500 characters.
ALTER TABLE notification_item DROP CONSTRAINT notification_item_link_check;
ALTER TABLE notification_item ADD CONSTRAINT notification_item_link_check
    CHECK (length(link) <= 500 AND link ~ '^/[A-Za-z0-9/_.~%?=&-]*$');
