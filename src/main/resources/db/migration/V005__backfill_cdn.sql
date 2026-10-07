-- Per-revision CDN progress for the upload phase of a backfill.
--
-- The import phase has a revision-level queue the dashboard can draw (pending/done/failed), but the
-- upload that follows it was one opaque line: every imported revision's sprites, models and rendered
-- images go up after the last import, and for the length of that the page could only say "uploading".
-- These columns are the same shape one level down — which revision is uploading, which asset kind,
-- and how many of that kind's objects have been PUT — so the bar moves file by file.
--
-- Lives here rather than in the backfilling process for the same reason the queue does: the API is a
-- different process and reports progress by reading this table.
ALTER TABLE backfill ADD COLUMN cdn_pending     integer[] NOT NULL DEFAULT '{}';
ALTER TABLE backfill ADD COLUMN cdn_done        integer[] NOT NULL DEFAULT '{}';
-- The revision being uploaded right now, and how far through its current asset kind it is.
ALTER TABLE backfill ADD COLUMN cdn_rev         integer;
ALTER TABLE backfill ADD COLUMN cdn_stage       text;
ALTER TABLE backfill ADD COLUMN cdn_files       integer NOT NULL DEFAULT 0;
ALTER TABLE backfill ADD COLUMN cdn_files_total integer NOT NULL DEFAULT 0;
ALTER TABLE backfill ADD COLUMN cdn_percent     smallint NOT NULL DEFAULT 0;
