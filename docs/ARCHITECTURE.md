# OpenRune diff platform: architecture assessment and redesign

This document records the assessment of the `.bin` based diff system and the design of its
PostgreSQL replacement. Numbers quoted here were measured on the development machine
(16 logical cores, 32 GB RAM, Windows 11, JDK 17, PostgreSQL 16 embedded for tests and
PostgreSQL 18 for the local benchmark) against OSRS revisions 1, 240 and 241 downloaded from
OpenRS2. See [BENCHMARKS.md](BENCHMARKS.md) for the full benchmark output.

## 1. Problems in the system this replaced

The file and line references in this section point at the `.bin` implementation as it stood before
the rewrite. Those files have since been deleted (see §10); they are in git history at commit
`5d56746` if you want to read the original.

### 1.1 Every query path materialises whole revisions in the JVM heap

* `DiffBinaryCache.getDecodedRev` (`cache/diff/DiffBinaryCache.kt:215`) decodes an entire
  `{rev}.bin` into `CacheBinaryFormat.DecodedRev`: every config type, every gameval group, every
  sprite PNG plus its SHA-256 and base64 raster, every map region and object placement, all
  client scripts, all interface manifests and all model metadata. The entry is never evicted
  (`"Full decoded payloads are not evicted once loaded"`, line 19).
* `DiffBinaryCache.getTypedCombinedConfig` (line 391) loops `for (r in 2..upToRev)` and calls
  `getDecodedRev` for every revision in between. Answering "items at rev 240" therefore pins
  roughly 200 decoded binaries in RAM permanently. The same loop exists in
  `getCombinedModels` (line 439), `getCombinedSprites` / `getCombinedSpriteData`
  (`DiffRoutes.kt:1829`, `1875`) and `ZipService.getTypedCombinedConfig`
  (`server/zip/ZipService.kt:284`).
* Measured: the legacy delta dump of one recent revision peaked at 7.0 to 7.5 GB of heap
  because it held the full rev 1 base, the full decoded current revision, all sprite PNGs and
  all 60k model summaries at once (revisions 240 and 241; see BENCHMARKS.md).

### 1.2 Diffs are computed by deep-comparing full snapshot maps on the request path

* `/diff/delta/summary` (`DiffRoutes.kt:1121`) materialises the full snapshot map of all 21
  config types at `base` and at `rev` and compares every entity with `Map.equals` on nested
  `FieldEntry` trees. `/diff/config/{type}/content` (line 1221) does the same and then renders
  every added, changed and removed entity into one JSON string that is cached in a 500-entry
  LRU (`DiffRouteCaches.configContent`).
* Sprite diffs decode PNGs with `ImageIO` and compare pixels (`areSpritesVisuallyEqual`,
  line 1934) for every common sprite id, on the request thread.

### 1.3 Search and pagination happen in Kotlin over complete lists

* `/diff/config/{type}/table` (line 1369) builds `allRows` for the whole type, filters with
  substring / regex / id-range matching in Kotlin, then `drop(offset).take(limit)`. The
  website's `table-all` Next.js route pages through this at 500 rows per call up to 100,000
  rows to run its own client-side search.

### 1.4 Unbounded, duplicated caches

`DiffRouteCaches` holds twelve LRUs sized by entry count (500 to 8,000 entries) that cache the
same data in several shapes: merged snapshot maps, row lists, serialised JSON strings, sprite
ETags and delta objects, all keyed by `(base, rev)` pairs. None are bounded by bytes, so
memory grows with the number of distinct revision pairs users look at.

### 1.5 The delta format is relative to rev 1 and replaying it is subtly wrong

`DiffDumper.deltaDump` (`cache/diff/DiffDumper.kt:736`) compares the current revision against
rev 1, not against the previous revision, and stores `added/removed/changed` relative to rev 1.
`getTypedCombinedConfig` then *replays* revisions 2..N sequentially as if each were an
incremental delta. An entity changed in rev 100 and reverted to its rev 1 value by rev 200 does
not appear in rev 200's delta, so the replay keeps the rev 100 value. The correct state at N is
`(base minus removed_N) union delta_N`, which only needs two binaries, so the replay is both
wasteful and incorrect for reverts.

### 1.6 No separation between "discovered", "processing" and "published"

`WebServer.start` (`server/WebServer.kt:436`) runs the dumper synchronously inside startup. One
mutable `config.revision` is the "server revision". If the dump fails the whole process goes to
`ERROR` and every non-whitelisted route returns the status payload. Processing a new revision
requires restarting the server (the production deploy workflow does a kill and start).

### 1.7 Process-global mutable state

`DiffDumper.companion` (`paramType`, `currentRev`, `gamevals`), `CacheManager` and
`XteaLoader` are global singletons used by the serialiser and the map extractor, so two
revisions cannot be processed concurrently and ingestion state leaks between runs.

### 1.8 Game separation is a deployment property only

`ServerConfig.gameType` selects one game per process, which is fine, but the ingestion code is
OSRS-only by construction (`DiffDumper.decodeConfigs` instantiates `OsrsCacheProvider`
decoders directly) and there is no decoder registry to plug an RS3 implementation into.

### 1.9 Route code mixes every concern

`DiffRoutes.kt` is 1,946 lines of HTTP parsing, caching, snapshot merging, diffing, ETag
computation, image resizing and CDN redirects. Helper functions (`fieldEntryToJson`,
`md5HexUtf8`, `parseIfNoneMatch`, `gamevalGroupNameForConfigType`) are duplicated between
`DiffRoutes.kt` and `CacheRoutes.kt`.

## 2. Proposed architecture

### 2.1 Overview

```
                 HTTP (Ktor)                                   Ingestion (background)
  routes/*.kt ─► query/*.kt ─► db/Database (Hikari, JDBC)      RevisionDiscovery (OpenRS2 poll)
                                      │                                 │
                                      ▼                                 ▼
                                PostgreSQL  ◄── ingest/IngestionPipeline ──► raw cache dir
                                                 download → decode → stage → merge → validate → publish
```

* **Storage decision:** one row per *entity version* with a validity range of revisions
  (`valid_from` inclusive, `valid_to` exclusive, `NULL` = still current). Payloads are
  content-addressed and stored once. This is the hybrid option: storage grows with the number
  of changes, not with revisions times entities; "state at R" and "A to B" are single range
  predicates; no chain replay anywhere.
* **Immutable published revisions.** A revision is queryable only after `revision.published`
  is set. Rows written for an unpublished revision never affect queries at published revisions
  (see 4.4), so ingestion of 235 runs while 234 is served, and a failed 235 is rolled back with
  one statement pair.
* **One process per game stream** (OSRS live, RS3 live, OSRS beta) remains the deployment
  model because the website already addresses `osrs.openrune.dev` and `rs3.openrune.dev`, but
  the schema carries `game_id` on every row and the ingestion decoders are resolved from a
  per-game registry, so one database can hold both and one process can serve both.

### 2.2 PostgreSQL schema

```
game                (id smallint PK, slug text unique, name text, environment text)
entity_type         (id smallint PK, game_id, key text, kind text, display_name text,
                     gameval_group text, UNIQUE(game_id, key))
revision            (game_id, rev int, PK(game_id, rev), source text, source_cache_id int,
                     source_timestamp timestamptz, status text, published bool,
                     published_at timestamptz, publish_stamp bigint, attempts int,
                     error text, metrics jsonb, created_at, updated_at)
ingest_run          (id bigserial PK, game_id, rev, started_at, finished_at, status,
                     stage text, error text, metrics jsonb)
entity_payload      (hash bytea PK, body jsonb, size int)
entity_blob         (hash bytea PK, bytes bytea, size int)      -- sprite PNGs, clientscripts
entity_version      (game_id smallint, type_id smallint, entity_id int,
                     valid_from int, valid_to int NULL, payload_hash bytea,
                     blob_hash bytea NULL, name text NULL,
                     ingest_rev int, closed_by_rev int NULL,
                     PK(game_id, type_id, entity_id, valid_from))
                     PARTITION BY LIST (game_id)
entity_stage        (UNLOGGED; same columns as a decoded entity; truncated per type)
map_region_object   (game_id, type_id, region_id int, valid_from int, object_id int)
                     -- which region versions place which object id
revision_artifact   (game_id, rev, kind text, blob_hash bytea)  -- xtea keys, manifests
schema_migration    (version int PK, applied_at)
```

Indexes on `entity_version`:

| index | supports |
|-------|----------|
| PK `(game_id, type_id, entity_id, valid_from)` | entity history, entity lookup at R, keyset paging by id |
| `(game_id, type_id, valid_from)` | "added or changed in (A, B]", ingestion merge |
| `(game_id, type_id, valid_to)` | "removed or changed in (A, B]" |
| `(game_id, type_id) WHERE valid_to IS NULL` (partial) | "latest" listings |
| `gin (name gin_trgm_ops)` when `pg_trgm` is available | substring and regex name search |

Why these and not more: every query filters on `(game_id, type_id)` and then on either the
entity id or a revision bound, so three B-tree indexes cover all access paths. Field-level
indexes on payload JSON were deliberately not added; the only fields the website filters on are
`name` and the gameval name, which are columns.

**Partitioning.** `entity_version` is LIST-partitioned by `game_id` with one partition per game
created automatically when a game row is inserted. This was chosen because it costs nothing at
OSRS scale, every query prunes to one partition, and it guarantees that RS3 ingestion (vacuum,
bloat, index growth; an RS3 cache is 25 GB versus 190 MB for OSRS) cannot change OSRS query
plans or lock OSRS partitions. Partitioning by entity type or revision was rejected: queries
would still touch a single partition, but each new type would need DDL and revision-range
partitions do not fit validity ranges that span revisions.

### 2.3 Entity types

Entity types are declared in code per game (`EntityTypeDef`), registered into `entity_type`
at startup, and cover everything the website shows:

* config types (`inv`, `overlay`, `underlay`, `npcs`, `items`, `objects`, `params`,
  `sequences`, `spotanims`, `enum`, `healthbar`, `mapelement`, `varp`, `varbit`,
  `worldentity`, `worldmaparea`, `struct`, `varclan`, `varclient`, `interfaces`, `textures`)
* archives: `sprites` (payload = sprite metadata, blob = PNG), `models` (payload =
  `ModelMeta`), `clientscripts` (blob = raw bytes), `map.regions` (payload = positions and
  tile ids)
* gameval groups: `gameval.items`, `gameval.npcs`, ... (payload = `{text, sub}`, `name` =
  searchable name)

Adding an RS3 type means adding an `EntityTypeDef` and a decoder; nothing in storage or the API
changes.

### 2.4 Ingestion pipeline

```
DISCOVERED → DOWNLOADING → PROCESSING → IMPORTING → VALIDATING → READY
                                                              ↘ FAILED (retryable)
```

* `RevisionDiscovery` polls `archive.openrs2.org/caches.json`, inserts `DISCOVERED` rows
  for new major revisions of the configured game.
* `IngestionWorker` runs at most one ingestion per game (PostgreSQL advisory lock on
  `game_id`), on its own small thread pool, with its own connection pool, so API traffic
  never waits on it.
* `PROCESSING/IMPORTING` is streamed per entity type: decode one type → serialise snapshots
  → `COPY` into `entity_stage` → set-based merge into `entity_version` in one transaction →
  truncate the stage table → next type. Peak heap is one type's definitions plus the open
  cache file, not the whole revision.
* `VALIDATING` checks row counts per type against decoded counts, that no entity has
  overlapping validity ranges, and that every referenced payload exists.
* `publish` flips `revision.published` in one transaction and bumps `publish_stamp`, which
  is the cache and ETag epoch.
* `rollback(rev)` deletes rows with `ingest_rev = rev`, reopens rows with
  `closed_by_rev = rev` and clears the revision, so a failed run can be retried from scratch.

### 2.5 API architecture

* `routes/*`: parse and validate parameters, call a query object, serialise. No caching logic,
  no merging.
* `query/*`: `RevisionCatalog` (published revisions, latest), `EntityQueries` (listing, search,
  lookup, history), `DiffQueries` (A to B sets and counts), `SpriteQueries`, `ModelQueries`,
  `MapQueries`. Each method is one or two SQL statements with `LIMIT`.
* `db/Database`: HikariCP pools (API pool with `statement_timeout`, ingestion pool), the
  migration runner, and a thin JDBC helper.
* Legacy endpoints are kept with identical response shapes (see 7). New endpoints for the
  developer UI live under `/admin/*` and are gated by a token or loopback.

### 2.6 Caching

Published revisions never change, so caching is keyed by the game's `publish_stamp`:

* ETags for every conditional endpoint are derived from `(publish stamp, endpoint, params)`.
  A `304` costs no database work.
* A single bounded in-process cache (Caffeine, weighed by bytes, 128 MB default) holds diff
  summaries, counts, gameval maps, sprite indexes and texture-usage indexes. No full revisions,
  no payload bodies.
* **Hot revisions are warmed, the rest are on demand.** The revisions users actually open are the
  base revision and the newest published ones, so `HotRevisions` precomputes their derived views
  into the cache at startup, after every publish, and on a 10-minute timer
  (`OPENRUNE_HOT_REVISIONS` sets how many newest, default 2). Every other revision answers the
  same queries at the same cost, just without the pre-warm — nothing is ever loaded wholesale, so
  an old revision is a normal indexed query rather than a 12-second decode.
* Nothing else is cached. PostgreSQL's buffer cache keeps hot index pages in memory.

### 2.7 Developer UI

The website keeps its revision and diff explorer, now backed by paginated SQL. New admin
endpoints expose revision status and stages, ingest runs with per-stage durations and counts,
row counts and table sizes, pool statistics, cache statistics, JVM memory and recent slow
queries. The website gets an ingestion status page under `/dev`.

### 2.8 Where raw data lives

Three copies exist deliberately, each with a different job:

* **PostgreSQL** holds queryable state: decoded payloads, plus sprite PNGs and client scripts as
  content-addressed blobs. This is what the API serves and what makes a sprite re-upload possible
  without any cache on disk.
* **The CDN** holds raw immutable assets per revision — `sprites/{id}.png`, `sprites.zip`,
  `textures.zip`, `models/{id}.dat`. The browser fetches these directly, so they never occupy API
  memory or bandwidth. `CdnPublisher` writes them after a revision publishes, and `publishCdn`
  backfills or repairs them later.

  A **full set is uploaded per revision**, not just the assets that changed. That is deliberate
  despite the object count (~70,600 per revision: 8,560 sprites + 62,041 meshes). Priced on R2,
  200 revisions is roughly 22 GB (~$0.33/month) and 14 M one-off writes (~$64); delta upload would
  save about $60 once and pennies a month. In exchange, `rev/{rev}/...` stays complete and
  immutable: the website builds asset URLs with no source-resolution round trip, every revision
  path works standalone, and browser caching is perfect. Several call sites rely on that —
  `RSSprite`, `RSTexture`, the config entity view and `modelDatUrl` all address assets by the
  revision being viewed rather than the revision an asset last changed in. Delta upload would
  require changing all of them first.
* **`cache/{game}/{env}/{rev}/`** holds the raw cache as downloaded from OpenRS2. It is the
  reprocessing source for a revision and the only source of raw mesh bytes, which are large,
  immutable and never queried — storing ~60k meshes per revision in the database would cost a lot
  for nothing. `RawCacheStore.prune(keep)` deletes old ones; OpenRS2 stays the archive of record
  and `publishCdn ... downloadCache=true` re-fetches on demand.

## 3. Data model: how an entity's history is stored

```
items #4151: [1, 150) hash A   [150, 200) hash B   [200, NULL) hash A
```

* at rev 100 → `valid_from <= 100 AND (valid_to IS NULL OR valid_to > 100)` → A
* history → `ORDER BY valid_from`
* diff 100 → 240: versions that *ended* in (100, 240] = `[1,150)`, `[150,200)`; versions that
  *started* in (100, 240] and are current at 240 = `[200, NULL)`. The old state is the one
  ended-version that was valid at 100 (`[1,150)`, hash A), the new state is hash A: equal, so
  the entity is reported as unchanged.

## 4. Query strategy

### 4.1 Listing / table at R (keyset and offset)

```sql
SELECT v.entity_id, v.name, g.name AS gameval
FROM entity_version v
LEFT JOIN entity_version g ON g.game_id = v.game_id AND g.type_id = :gamevalType
     AND g.entity_id = v.entity_id AND g.valid_from <= :r AND (g.valid_to IS NULL OR g.valid_to > :r)
WHERE v.game_id = :g AND v.type_id = :t
  AND v.valid_from <= :r AND (v.valid_to IS NULL OR v.valid_to > :r)
  AND v.entity_id > :after
ORDER BY v.entity_id LIMIT :limit
```

The legacy API is offset-based; offsets are still accepted (bounded: `limit <= 500`) and a
cursor (`after`, `nextCursor`) is also returned so clients can move to keyset paging.

### 4.2 Search

`name` mode: `v.name ILIKE :pattern OR v.entity_id::text LIKE :pattern`; `regex` mode:
`v.name ~* :regex`; `gameval` mode: join on the gameval type and match tokens; `id` mode:
`entity_id = ANY(:ids)` or `BETWEEN`. All with the same validity predicate and `LIMIT`.

### 4.3 Diff A → B

```sql
WITH old AS (SELECT entity_id, payload_hash FROM entity_version
             WHERE game_id=:g AND type_id=:t AND valid_from <= :a AND valid_to > :a AND valid_to <= :b),
     new AS (SELECT entity_id, payload_hash, name FROM entity_version
             WHERE game_id=:g AND type_id=:t AND valid_from > :a AND valid_from <= :b
               AND (valid_to IS NULL OR valid_to > :b))
SELECT COALESCE(o.entity_id, n.entity_id) AS id,
       CASE WHEN o.entity_id IS NULL THEN 'added'
            WHEN n.entity_id IS NULL THEN 'removed' ELSE 'changed' END AS kind
FROM old o FULL OUTER JOIN new n USING (entity_id)
WHERE o.entity_id IS NULL OR n.entity_id IS NULL OR o.payload_hash <> n.payload_hash
ORDER BY id
```

Both CTEs are index range scans on `(game_id, type_id, valid_to)` and
`(game_id, type_id, valid_from)`. Cost is proportional to the number of changes between A and
B, never to the number of revisions between them or the number of entities.

### 4.3.1 Reading the contents of a diff

`DiffQueries.changesPage` wraps the query above in a `page` CTE that applies the kind filter, the
`entity_id > :after` cursor and `LIMIT`, and only then joins `entity_payload` for the old and new
bodies. Because the `LIMIT` is inside the CTE, the only payloads fetched and compared are the ones
being returned: a first page costs the same on a base-to-tip diff as on an adjacent one, give or
take the range scan. The changed-field reduction runs in the JVM rather than in SQL — measured at
half the cost of `jsonb_each` + `jsonb_object_agg`, see BENCHMARKS.md.

`GET /diff/config/{type}/changes?base=&rev=&kind=&limit=&after=` serves it, returning
`{counts, total, nextCursor, hasMore, rows:[{id, kind, changedInRev, name, gameval, fields}]}`.
`fields` is the new payload for an added entity, `{field:{from,to}}` for a changed one, and absent
for a removed one. The query fetches `limit + 1` rows so `nextCursor` is exact without a probe
request, and gameval names coalesce the new revision's over the base revision's so removed entities
keep the name they had.

The website walks these pages in presentation order (added, then changed, then removed) and renders
each as it arrives, so the first screen paints after one ~44 ms request rather than after a
multi-megabyte whole-diff response. `/diff/config/{type}/content` still returns the entire diff in a
single streaming pass for callers that want it; nothing in the site asks for it any more.

### 4.4 Why ingestion does not disturb readers

For an append (R greater than every ingested revision) the merge only (a) sets
`valid_to = R` on versions whose entity changed or disappeared and (b) inserts versions with
`valid_from = R`. For any published R' < R, (a) keeps `valid_to > R'` and (b) is excluded by
`valid_from <= R'`. For an out-of-order insert the merge runs in one transaction per type and
the only rows affected are the covering versions of entities that changed at R.

## 5. Memory strategy

* The API never holds a revision. Every endpoint is a `LIMIT`ed query; detail endpoints read
  one payload row.
* Ingestion decodes one entity type at a time; snapshots are written through `COPY` from an
  iterator, so peak heap is one type's definitions (the largest OSRS type, models, is 60k
  small records).
* Sprite change detection hashes the decoded raster once at ingestion; no PNG decoding on the
  request path.
* The only application cache is weighed by bytes with a fixed ceiling.

## 6. Migration strategy

1. Schema, ingestion, queries and routes are added alongside the legacy code (this change).
2. `LegacyBinImportMain` imports every existing `{rev}.bin` into PostgreSQL without
   re-downloading caches: state(N) = (rev 1 base minus removed_N) union delta_N, so each
   revision needs only bins 1 and N. Sprites not stored in bins are fetched from the CDN or
   recorded as metadata-only.
3. `ValidateAgainstLegacyMain` compares, for every revision pair and type, the legacy diff
   result with the PostgreSQL result (id sets and per-field changes) and reports differences.
   The known, explainable difference is the revert case described in 1.5.
4. The server starts in PostgreSQL mode when `OPENRUNE_DATABASE_URL` is set; the website is
   unchanged because the response shapes are preserved (section 7).
5. Once a deployment runs on PostgreSQL with the imported history, the legacy `.bin` reader,
   dumper and route files are deleted (tracked in 9).

## 7. API compatibility

Preserved verbatim (the website reads exactly these fields, verified against the Next.js
source): `/status`, `/sse` (STATUS, ZIP_PROGRESS), `/diff/revisions`, `/cache/nav`,
`/cache/gameval/groups`, `/gameval/{type}`, `/diff/support/manifest`,
`/diff/config/{type}/table|content|props`, `/cache`, `/diff/combined/sprites`,
`/diff/delta/sprites`, `/diff/delta/sprites/summary`, `/diff/delta/summary`, `/sprites`,
`/diff/sprite/{id}`, `/models/*`, `/textures/*`, `/zip/*`, `/endpoints/data`, `/api`,
`/revisions`, `/config-types`. Endpoints the website does not call (`/diff/manifest/{rev}`,
`/diff/interface/manifest`, `/diff/clientscripts/*`, `/interface/{id}`, `/map/*`,
`/sprites/raw`, `/diff/sprite/{id}/raw`) are kept with the same shapes.

Behavioural changes:

* `/diff/decode/status` always reports `ready` for published revisions; the 202 "decoding"
  path no longer exists because nothing is decoded on demand.
* `/status.revision` is the latest *published* revision, never a revision in progress.
* List endpoints additionally return `nextCursor` and accept `after`.

New endpoints:

* `/diff/config/{type}/changes` — paginated `/content` (§4.3.1). The diff text views read this
  instead of `/content`; `/content` itself is unchanged and still served.
* `/diff/entity/{type}/{id}/history` — every revision an entity changed in, which the validity-range
  model makes a single range scan and the `.bin` format could not answer at all.

## 8. Performance risks

* **Deep offsets.** `OFFSET 400000` on an RS3-sized type scans 400k index entries. Mitigated
  by the cursor API and `limit <= 500`; the website's bulk scans should move to cursors.
* **Gameval joins.** Each listing joins a second range scan. Measured at well under a
  millisecond per row for OSRS; if RS3 makes this noticeable, the gameval name can be
  denormalised into the config row at ingestion.
* **Out-of-order ingestion.** Correct but touches more rows (copies of covering versions).
  Expected to be rare (retrying a skipped revision after a newer one was published).
* **Payload table growth.** Content addressing means reverts cost nothing, but RS3 may have
  millions of distinct payloads. TOAST compression (lz4 where available) keeps this on disk,
  not in RAM.
* **COPY throughput on slow disks.** Ingestion is bounded by PostgreSQL write speed; the
  stage table is `UNLOGGED` and merges are set-based, so a full OSRS revision imports in
  seconds once decoded.

## 9. Benchmark plan

Measured with `BenchmarkMain` (same JVM flags for both systems, 3 warm-up and 10 timed
iterations, p50 and p95 reported) and recorded in `BENCHMARKS.md`:

| area | legacy | new |
|------|--------|-----|
| revision list | `getRevisionsWithData` | `RevisionCatalog.published` |
| entity search | table route search path over `allRows` | `EntityQueries.search` |
| entity lookup | `getTypedCombinedConfig(type, rev)[id]` | `EntityQueries.get` |
| adjacent diff | legacy summary computation 240 → 241 | `DiffQueries.summary` |
| distant diff | 1 → 241 | same |
| large result set | content endpoint | paged diff listing |
| concurrent requests | 16 threads mixed | same |
| ingestion | dumper timing and peak heap | pipeline stage timings and peak heap |
| synthetic scale | n/a | generated dataset: 200 revisions, 2M entities, 3% churn |
| memory | peak heap after warming 3 revisions | peak heap after the same request mix |

Correctness is checked first with `ValidateAgainstLegacyMain`; performance claims are only
made for results whose outputs matched.

## 10. Removed code

The `.bin` system is gone. Raw assets still go to the CDN exactly as before — mesh and sprite
extraction, the uploaders and the repair path are all intact, now driven by `CdnPublisher` from
either ingestion or the `publishCdn` task.

Deleted: the Ktor server and every legacy route file
(`DiffRoutes` was 1,946 lines, plus `CacheRoutes`, the model / texture / map / zip endpoints and
`DiffRouteCaches`' twelve LRUs), the in-memory `DiffBinaryCache`, `DiffDumper`,
`WebCacheManager`, the checksum manifest classes, the `.bin` writer, the standalone dumper entry
points, the cache-index enums, `JagexColor`, the per-region height computation, and the
reflection-based query-parameter documentation. The two CDN migration CLIs were folded into
`publishCdn`, which gained `kinds=`, `dryRun` and model repair in the process.

Kept, because the new system needs them:

| Kept | Why |
|------|-----|
| `CacheBinaryFormat` (decode only) | imports legacy history and backs the validation tool |
| `ConfigDiffType`, `ConfigSerializer` | the per-type field, column, render and search declarations the UI reads |
| `cache/map/*`, `ModelExtractor`, `SpriteCdn`, `ModelCdn` | decoding a raw cache and publishing its assets |
| `cdn/CdnPublisher` + `publishCdn` | replaces the two migrate CLIs: backfill and repair the CDN for already-published revisions |
| `EndpointRegistry` | self-documentation for `/api`, now a plain registry |

The one shared mutable singleton still in the ingest path is the upstream library's
`CacheManager`; nothing in the new code writes it.
