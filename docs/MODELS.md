# Models

Two halves: raw meshes go to the CDN, per-model metadata goes into PostgreSQL.

## Meshes (CDN)

```
{osrs|rs3}/rev/{rev}/models/{id}.dat
```

Raw index-7 archive bytes, one object per model, a **full set per revision** so a rev path is
self-contained (same rule as sprites). Uploaded after a revision is published; see
[SPRITE_CDN.md](SPRITE_CDN.md) for the bucket/credential env vars — models reuse the same CDN
config. The website loads `.dat` files straight from the CDN, never through the API.

## Metadata (`models.json` → PostgreSQL)

Meshes are decoded **once per revision**. The result is written to a sidecar next to that
revision's downloaded cache:

```
cache/{game}/{env}/{rev}/models.json
```

A re-ingest of the same revision reads that JSON instead of decoding ~60k meshes again. Delete the
file to force a rebuild. Entry keys are short because a revision holds ~60k of them:

```json
{"1234":{"v":124,"f":198,"tf":4,"ver":13,"pri":0,"tex":[52],"col":[8128],"items":[4151],"npcs":[],"objs":[]}}
```

| JSON key | `ModelMeta` field | Meaning |
|----------|-------------------|---------|
| `v` / `f` | `vertexCount` / `faceCount` | mesh size |
| `tf` | `texturedFaceCount` | number of texture triangles |
| `af` | `transparentFaceCount` | faces with a non-zero alpha (not fully opaque) |
| `ver` / `pri` | `version` / `renderPriority` | mesh header values |
| `tex` | `textures` | distinct texture ids used by faces |
| `col` | `colors` | distinct HSL face colours |
| `items` / `npcs` / `objs` | `itemIds` / `npcIds` / `objectIds` | reverse attachments |

Each entry becomes one `models` entity version in PostgreSQL, so a model that does not change
between revisions is stored once and its validity range simply extends. Attachments come from
`inventoryModel` plus the male/female worn and head model fields on items, `models` +
`chatheadModels` on npcs, and `objectModels` on objects.

## API

| Endpoint | Purpose |
|----------|---------|
| `GET /models/{id}?rev=` | One model: counts, textures, colours, attachments (id + gameval name), CDN `.dat` url |
| `GET /models?rev=&ids=1,2,3` | Batch lookup; also accepts `idRange=300..900` and `limit` |
| `GET /models/table?rev=&offset=&limit=&q=` | Paginated rows for the models archive table (id, verts, triangles, transparency) |
| `GET /models/delta?base=&rev=` | Model ids added / removed / changed between two revisions |
| `GET /models/for/{type}/{id}?rev=` | Models used by one item / npc / object, each with metadata, plus combined totals |
| `GET /models/info?rev=` | Aggregate stats for a revision |

`/models/for` accepts `items`, `npcs` or `objects`. Model ids are read from the definition's stored
payload (`inventoryModel` + worn/head fields, `models` + `chatheadModels`, `objectModels`), so ids
the definition points at but which have no metadata are reported under `totals.missingModels`.

`/models` requires one of `ids`, `idRange` or `limit` — the full set is too large to return.
Responses are capped at 500 entries. `/models/table` also returns `nextCursor`; pass it back as
`after=` to page without an offset scan.

## Texture usage

"What uses texture 9" is the inverse of the model metadata, so nothing extra is stored for it.

- **models** — the model's `tex` list
- **items / npcs / objects** — one list each, merging every way a definition reaches the texture:
  a model of theirs has it on a face, they retexture *from* it (`originalTextureColours`), or they
  retexture *to* it (`modifiedTextureColours`, so it is what actually renders)
- **overlays** — `OverlayType.texture`, which names a texture directly rather than via a model

| Endpoint | Purpose |
|----------|---------|
| `GET /textures/{id}/usage?rev=` | Full usage for one texture, plus its `fileId`, name, `averageRgb`, transparency and animation fields |
| `GET /textures/usage?rev=` | Every texture with usage counts — a browsable index |

The index is built by streaming the revision's model and config payloads, then cached per revision
and pre-warmed for the hot revisions (see ARCHITECTURE.md §2.6).

## Backfilling

Metadata is part of normal ingestion; mesh uploads can be re-run on their own:

```bash
./gradlew ingestRevisions -PtoolArgs="revs=241"                            # decode, import, publish, upload
./gradlew publishCdn -PtoolArgs="revs=241 kinds=models"                    # re-upload .dat only
./gradlew publishCdn -PtoolArgs="kinds=models repair=true"                 # fill gaps across every revision
./gradlew publishCdn -PtoolArgs="revs=241 kinds=models downloadCache=true" # raw cache no longer on disk
```

Model uploads need the revision's raw cache because the meshes are not stored in the database;
`downloadCache=true` fetches it from OpenRS2 first. See [SPRITE_CDN.md](SPRITE_CDN.md) for the full
flag list.

Revisions whose history only exists as legacy `.bin` files are imported with
`./gradlew importLegacyBins`, which reads the model metadata out of the bin's `MDLM` trailer. Those
revisions have no mesh bytes in the database either, so their `.dat` upload also needs
`downloadCache=true`.
