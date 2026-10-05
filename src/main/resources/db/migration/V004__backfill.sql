-- A backfill is a queue of older revisions to import, worked newest first. It lives in the
-- database rather than in the backfilling process so the API can report progress without being
-- that process — the same reason ingest progress moved here in V003.
--
-- One row per game: a backfill is a single long-running job, not something you run twice at once.
CREATE TABLE backfill (
    game_id    smallint PRIMARY KEY REFERENCES game (id) ON DELETE CASCADE,
    pending    integer[] NOT NULL,
    done       integer[] NOT NULL DEFAULT '{}',
    failed     integer[] NOT NULL DEFAULT '{}',
    -- Set while the worker steps aside to import a newly released cache.
    paused_for integer,
    started_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
