-- Fine-grained progress for a running ingestion. `stage` alone is too coarse for a progress bar,
-- and it used to live only in the ingesting process's memory, so the API could not show progress
-- for an ingestion running outside it (the scheduled job). Persisting it here makes the ingestion
-- page work the same whichever process is importing.
ALTER TABLE ingest_run ADD COLUMN percent smallint;
ALTER TABLE ingest_run ADD COLUMN message text;

-- The API polls for the one running row; keep that lookup off a sequential scan as runs accumulate.
CREATE INDEX ingest_run_active_idx ON ingest_run (game_id, id DESC) WHERE finished_at IS NULL;
