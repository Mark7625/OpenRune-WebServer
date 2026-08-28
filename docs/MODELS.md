# Models

Two halves: raw meshes go to the CDN, per-model metadata goes into the revision binary.

## Meshes (CDN)

```
{osrs|rs3}/rev/{rev}/models/{id}.dat
```

Raw index-7 archive bytes, one object per model, a **full set per revision** so a rev path is
self-contained (same rule as sprites). Uploaded during every dump; see [SPRITE_CDN.md](SPRITE_CDN.md)
for the bucket/credential env vars — models reuse the same CDN config.

## Metadata (`models.json` → `.bin`)

Meshes are decoded **once per revision**. The result is written to a sidecar next to that
revision's downloaded cache:

```
cache/{game}/{env}/{rev}/models.json
```

Every later dump or migrate run reads that JSON instead of decoding ~60k meshes again. Delete the
file, or pass `-PcdnForce=true`, to rebuild it.

The same JSON is what gets embedded in the revision binary, under an `MDLM` trailer — the meshes
themselves are never stored in a bin. Entry keys are short because a revision holds ~60k of them:

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

Decoded into `ModelMeta`:

Rev 1 stores the full set; later revisions store only added/changed entries plus an
added/removed/changed summary, exactly like config diffs. The trailer is marker-prefixed, so bins
written before models existed still decode (they just report no models).

Attachments come from `inventoryModel` + the male/female worn and head model fields on items,
`models` + `chatheadModels` on npcs, and `objectModels` on objects.

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
snapshot (`inventoryModel` + worn/head fields, `models` + `chatheadModels`, `objectModels`), so ids
the definition points at but which have no metadata are reported under `totals.missingModels`.

`/models` requires one of `ids`, `idRange` or `limit` — the full set is too large to return.
Responses are capped at 500 entries.

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

The index is built once per revision from the merged model set and config snapshots, then cached.

## Backfill

Revisions dumped before models existed can be filled in without a re-dump. The cache for each
revision must already be on disk (`./gradlew runDownloadAllCaches`):

```bash
./gradlew migrate240                                          # rev 240: model .dat + textures.zip + 240.bin metadata
./gradlew uploadModelsToCdn -PcdnFrom=240 -PcdnTo=240         # upload .dat only, no bin, no textures.zip

./gradlew migrateModelsToCdn                                  # every rev that has a cache
./gradlew migrateModelsToCdn -PcdnFrom=1 -PcdnTo=1            # base rev first — keeps later deltas small
./gradlew migrateModelsToCdn -PcdnDryRun=true                 # decode + count only
./gradlew migrateModelsToCdn -PcdnSkipCdn=true                # metadata only, no uploads
./gradlew migrateModelsToCdn -PcdnSkipBin=true                # uploads only, leave bins alone
./gradlew migrateModelsToCdn -PcdnForce=true                  # re-decode meshes, rebuild models.json
./gradlew migrateModelsToCdn -PcdnSkipTextures=true           # models only, no textures.zip
```

`migrate240` and `uploadModelsToCdn` are just `migrateModelsToCdn` with defaults pre-set; every
`-Pcdn*` property still overrides them (e.g. `./gradlew migrate240 -PcdnFrom=241 -PcdnTo=241`).

`textures.zip` is built from the merged texture config: each texture's `fileId` is fetched from the
sprites already on the CDN for that revision, and only if some are missing does the task fall back to
decoding the revision's sprite index.

The task rewrites each `.bin` in place, preserving everything already in it and adding the model
trailer. Attachments are rebuilt from the config snapshots already stored in the bins, so the config
archives are never decoded twice — and meshes are only decoded when a revision has no `models.json`
yet, so re-runs are cheap.

Run rev 1 first: deltas for later revisions are computed against rev 1's model set, and if that is
empty every model is recorded as "added".
