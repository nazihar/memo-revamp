-- The product is named `memo` (STATUS assumption A1); V1 wrote the repository name. V1 is already applied, so it stays
-- unchanged and the marker is corrected here. Runs as the migrator; the app role still has no write access.
UPDATE system_info SET schema_marker = 'memo' WHERE id = 1;
