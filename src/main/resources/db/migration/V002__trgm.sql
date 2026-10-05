-- Optional: trigram index for substring / regex name search. Skipped when the extension cannot
-- be created (the migration runner marks it as optional); ILIKE then falls back to a scan.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS entity_version_name_trgm_idx ON entity_version USING gin (name gin_trgm_ops);
