# Measurements

Hardware: 16 logical cores, 32 GB RAM, Windows 11, NVMe. JVM: Corretto 17. PostgreSQL 18 local,
`shared_buffers=512MB`, `work_mem=48MB`. Dataset: OSRS revisions 1, 240 and 241 from OpenRS2
(cache ids 241, 2720, 2727) — 438,597 entities at revision 241 across 39 entity types.

Each timed figure is p50 of 10 iterations after 3 warm-ups, from `./gradlew benchmark`. Peak heap is
the JVM heap-pool high-water mark, not an average.

Legacy numbers were captured with the `.bin` dumper and in-memory diff cache before that code was
removed; reproducible at git `5d56746`.

## Query paths

| operation | legacy | PostgreSQL | |
|-----------|-------:|-----------:|---|
| cold start | 12,230 ms | **768 ms** | 16x faster |
| peak heap, same workload | 5,131 MB | **203 MB** | 25x less |
| revision list | 0.43 ms | **0.00 ms** | cached |
| entity lookup items#4151 | 0.00 ms | 0.98 ms | slower: a query, not a `HashMap` hit |
| entity search items name~dragon (page 50) | 23.41 ms | **10.78 ms** | 2x faster |
| items page offset 5000 limit 50 | 3.84 ms | 25.04 ms | slower, see below |
| items page keyset after 5000 limit 50 | n/a | **8.52 ms** | the replacement for deep offsets |
| diff summary adjacent 240→241, all 21 config types | 717.89 ms | **10.27 ms** | 70x faster |
| diff summary distant 1→241 | 53.12 ms | 106.72 ms | see note |
| diff content items 1→241, whole set in one pass | 372.46 ms | 1,019 ms | slower, see below |
| diff first page items 1→241 (100 rows) | n/a | **25.04 ms** | what the website now asks for |
| diff first page items 240→241 (100 rows) | n/a | **0.14 ms** | |
| sprite delta 240→241 | n/a | **0.71 ms** | |
| entity history for one id | n/a | **0.27 ms** | new capability |
| mixed, 16 threads × 30 | p50 11.42 / p95 1,467 / **29 req/s** | p50 16.47 / p95 **60.56** / **631 req/s** | 22x throughput, 24x better tail |

### The number that matters most

Legacy concurrency: p50 11 ms but p95 1,467 ms and a 3,197 ms worst case at 29 requests/second.
That spread is the merged-snapshot cache rebuilding under a lock while every other request waits —
what users experienced as the site stalling. PostgreSQL: p95 60 ms at 631 requests/second, because
each request is an indexed query bounded by its page size with no shared lock.

### Where legacy still wins, and why that is accepted

* **Entity lookup (0.00 vs 0.98 ms).** Legacy read a `HashMap` already in heap. Matching that is
  not the goal; not paying 4 GB resident and a 12 s cold start for it is.
* **Deep offset (3.84 vs 25.04 ms).** `OFFSET 5000` still walks 5,050 index entries. Legacy sorted
  a map it had already spent 12 s and 4 GB building. The fix is the cursor: keyset paging is
  8.52 ms and does not degrade with depth, and list endpoints now return `nextCursor`.
* **`diff summary distant 1→241` (53.12 vs 106.72 ms).** The legacy figure is misleading: it was
  fast only because revision 241's merged snapshot was already cached by an earlier operation in
  the same run. Its true cold cost is the adjacent-pair figure, 717.89 ms, which is 70x slower
  than the new path.
* **`diff content items 1→241` whole set (372 vs 1,019 ms).** Reducing the entire base-to-tip diff
  in one pass is still slower than legacy: it fetches both payloads for all 34,607 changed items,
  parses ~70,000 JSON bodies and diffs them field by field at about 19 µs per parse. Legacy had the
  snapshots pre-parsed in RAM, having paid 4 GB for the privilege. This is accepted because nothing
  asks for the whole set any more — see *Diff content* below.

## Diff content

Opening a config diff used to mean one `/diff/config/{type}/content` request carrying every change.
At base-to-tip that is a multi-megabyte response the browser cannot show anything from until the
last byte arrives. `/diff/config/{type}/changes` returns one page instead, and the website walks the
pages in presentation order (added, then changed, then removed), rendering each as it lands.

Measured over HTTP against a local server with `node tools/bench-first-paint.mjs` — end to end,
including JSON serialisation and transfer, which the in-process figures above exclude:

| diff | `/content` | first `/changes` page | full page walk |
|------|-----------:|----------------------:|---------------:|
| items 1→241 | 24,686 ms, 13.20 MB | **44 ms, 0.05 MB** | 3,252 ms, 71 requests |
| objects 1→241 | 26,018 ms, 15.33 MB | **44 ms, 0.04 MB** | 4,911 ms, 101 requests |
| npcs 1→241 | 23,921 ms, 12.49 MB | **23 ms, 0.07 MB** | 1,650 ms, 35 requests |
| interfaces 1→241 | 55,782 ms, 27.96 MB | **106 ms, 2.59 MB** | 1,549 ms, 4 requests |
| items 240→241 | 170 ms, 1.16 MB | **18 ms** | 49 ms, 3 requests |
| varbit 240→241 | 172 ms, 0.46 MB | **15 ms** | 47 ms, 3 requests |

Time to first rendered line drops from ~25 s to ~44 ms on the worst diff in the dataset, and even
reading the *entire* diff is 5–36x faster than the single response, because each page carries only
the changed fields rather than both full payloads. Pages are immutable per published revision pair,
so each one is ETag-cached in IndexedDB independently.

Two scripts check the paged form against the single response (see `tools/README.md`):

* `tools/verify-changes-vs-content.mjs` reassembles the pages and compares bodies, removed ids and
  gameval names field for field against `/content`.
* `tools/verify-rendered-lines.mjs` goes further and runs the *website's own*
  `configLinesFromDiffBody` over both forms, comparing the rendered lines element by element.

Both report **identical** for items, objects, npcs, sequences, spotanims, interfaces and params at
1→241, and varbit, varp, inv, enum and struct at 240→241 — up to objects 1→241 at 577,185 rendered
lines. That equivalence is what lets the paged path reuse the existing renderer untouched.

### Two hypotheses this measured and rejected

1. **Compute the changed-field set in SQL.** `jsonb_each` + `jsonb_object_agg` + `jsonb_strip_nulls`
   took **2,184 ms** against **1,014 ms** for shipping both bodies and diffing them in the JVM, over
   the same 34,607 entities. PostgreSQL keeps `jsonb` in a binary form it must re-serialise per
   field; Gson parsing a `text` body once is cheaper. The database returns both bodies.
2. **Serve `/content` by walking its own pages.** Reusing the paged query to build the whole-set
   response cost **1,828 ms** against **1,019 ms** for a single streaming pass — the per-page `LIMIT`
   sort and repeated CTE evaluation are pure overhead when the answer is "all of it". `/content` is
   still one pass; pagination was the win, not page-shaped internals.

What makes the first page fast is the `LIMIT` *inside* the CTE: only the rows being returned have
their payloads fetched and compared, so cost tracks page size rather than the size of the diff.

### Fixes this benchmark found

Running it caught two real defects, both since fixed:

1. `page()` computed `total` with the gameval `LEFT JOIN` attached, costing one joined lookup per
   matching row — 34,607 of them for items. The join is 1:0..1 so it cannot change `count(*)`; it
   is now only included when a gameval *filter* needs it. Search went 46.1 → 10.8 ms, keyset paging
   61.6 → 8.5 ms, deep offset 88.9 → 25.0 ms, throughput 327 → 631 req/s.
2. Name search always OR-ed in `entity_id::text LIKE ?`, which can never match a query containing
   no digits yet stopped the planner using an index. Now added only when the query could match a
   number. Regex search deliberately keeps both sides: `\d` and `.` contain no literal digit but
   still match ids.
3. `/changes` joined gameval names only at the newer revision, so a *removed* entity — which by
   definition has no row there — came back nameless and the website would have labelled it with a
   generated `[type_id]` fallback. `/content` had always merged in the base revision's names. The
   page query now coalesces the two, and only adds the second join when the page can contain
   removals. Caught by `tools/verify-changes-vs-content.mjs` on varbit 240→241 (41 removals, 1
   named) — the one revision pair in the dataset that has any removals at all.

## Ingestion

| revision | legacy `.bin` dump | legacy peak heap | PostgreSQL pipeline | PostgreSQL peak heap |
|----------|-------------------:|-----------------:|--------------------:|---------------------:|
| 1 (89,375 entities) | 13.8 s | 1,591 MB | 24.1 s | **288 MB** |
| 240 (438,391 entities) | 64.0 s | 7,028 MB | 88.4 s | **645 MB** |
| 241 (438,597 entities) | 58.2 s | 7,554 MB | 79.1 s | **668 MB** |

Ingestion is slower in wall time and 11x smaller in peak memory. That is the intended trade:

* The legacy dump wrote one compressed file containing only what changed against revision 1. The
  pipeline writes every entity as a row, hashes each payload, and runs a COPY plus a set-based
  merge per type — 438k entities at revision 240 against a single `.bin` write.
* Peak heap no longer scales with revision size. The legacy delta dump held the decoded base
  revision, the decoded current revision, every sprite PNG and all 60k model summaries
  simultaneously; 7.5 GB was its steady state and the reason it ran with `-Xmx8G`. The pipeline
  holds one entity type at a time and fits in 1 GB.
* Ingestion is off the request path. Slower background import costs nothing user-visible; the
  memory it used to need was permanently reserved on the box serving the site.

Slowest types at revision 240: sprites 20.7 s (PNG encode plus pixel hashing), map regions 13.8 s
(2,937 squares), items 6.0 s (34,604 entities). Per-stage timings are stored in `revision.metrics`
and shown on the ingestion dashboard.

## Storage

Revisions 1, 240 and 241; 1.26 GB on disk including indexes and WAL.

| table | size | rows |
|-------|-----:|-----:|
| `entity_payload` | 274.1 MB | 457,124 |
| `entity_version` | 99.7 MB | 494,726 |
| `entity_ref` | 15.4 MB | 187,220 |
| `entity_blob` (every sprite PNG + client script) | 13.3 MB | 17,566 |

Content addressing is why blobs are negligible: revision 240 → 241 changed 17 sprites, so a new
revision adds ~17 blobs rather than another 8,560. Decoded payloads, not raw bytes, are the growth
driver.

## Correctness

`./gradlew validateLegacy -PtoolArgs="revs=1,240,241"` compares, for every entity type:

1. the id set and payload hash of every entity at every revision, against the state rebuilt from
   the `.bin` files;
2. added / removed / changed sets for each revision pair, plus the per-field change set of every
   changed entity.

**Result: no differences.** Report: `build/validation-report.md`.

One difference is expected rather than a bug, and did not arise in this dataset: the legacy delta
format stored each revision against revision 1 and the old reader replayed revisions 2..N in order,
so an entity changed in an intermediate revision and later reverted kept the intermediate value.
Validity ranges compare actual endpoint states, so a revert correctly reports as unchanged
(ARCHITECTURE.md §1.5).

## Scale beyond today's OSRS

`./gradlew syntheticScale -PtoolArgs="revs=50 types=20 entities=100000"` generates 2M entities per
revision across 50 revisions at 3% churn — roughly the shape an RS3 stream implies — and reports
per-revision import time, table sizes and query latency at that size. Report: `build/synthetic.md`.
