-- Revisions added by hand from a cache link instead of being found on OpenRS2.
--
-- `revision.source` already distinguishes where a revision came from ('openrs2' by default); this
-- is the other half of it for `source = 'manual'`: the exact `disk.zip` URL the raw cache is
-- downloaded from. Keeping it on the row rather than in a side table means a manual revision is
-- re-ingestable and re-downloadable long after the link was pasted, with no extra lookup.
ALTER TABLE revision ADD COLUMN source_url text;
