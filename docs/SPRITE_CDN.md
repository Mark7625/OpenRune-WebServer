# Sprite CDN (CloudFront / S3 / Cloudflare R2)

Ingestion uploads PNGs plus a zip after a revision is published.

## Layout

```
{osrs|rs3}/rev/{rev}/sprites/{id}.png
{osrs|rs3}/rev/{rev}/sprites.zip
{osrs|rs3}/rev/{rev}/textures.zip
{osrs|rs3}/rev/{rev}/models/{id}.dat
```

`textures.zip` holds `{textureId}.png` — each texture's `fileId` resolved against that revision's
sprites. Zip only; textures have no per-id CDN objects.

`models/{id}.dat` is the raw mesh straight out of index 7, a full set per revision. Model
*metadata* is not on the CDN — it lives in PostgreSQL and is served by `/models`; see
[MODELS.md](MODELS.md).

Uploads happen **after** the revision is published, so a CDN failure never blocks or unpublishes a
revision; it is logged and counted as `ingest.cdn.failed` on `/admin/metrics`.

## Server env

Copy `.env.example` → `.env` (loaded automatically at startup).

| Variable | Purpose |
|----------|---------|
| `OPENRUNE_CDN_BUCKET` / `R2_BUCKET_NAME` | Bucket (enables upload) |
| `OPENRUNE_CDN_BASE_URL` / `R2_PUBLIC_BASE_URL` | Public CDN URL, e.g. `https://cdn.openrune.dev` |
| `OPENRUNE_CDN_ENDPOINT` / `R2_ACCOUNT_ID` | R2 API endpoint (`https://{accountId}.r2.cloudflarestorage.com`) |
| `OPENRUNE_CDN_REGION` | Region (`auto` for R2; default `us-east-1` for AWS) |
| `R2_ACCESS_KEY_ID` / `AWS_ACCESS_KEY_ID` | Upload credentials |
| `R2_SECRET_ACCESS_KEY` / `AWS_SECRET_ACCESS_KEY` | Upload credentials |
| `OPENRUNE_CDN_ENABLED` | Optional override (`true`/`false`) |

Sprite bytes are always stored in PostgreSQL (`entity_blob`, deduplicated by content), so the API
can serve and resize a sprite with no CDN configured at all.

## Website

Hardcoded to `https://cdn.openrune.dev` in `cache-api-client.ts` (`SPRITES_CDN_BASE`). No env vars.

Full-size sprites load from the CDN. Resized requests hit `/sprites?width=&height=`, which reads
the stored bytes and resizes them; if a sprite is missing locally the route redirects to the CDN.

## Re-uploading and repairing

`publishCdn` uploads assets for revisions that are **already published**, without touching their
data or their published state:

```bash
# Everything for one revision
./gradlew publishCdn -PtoolArgs="revs=241"

# Every published revision, only the objects that are missing (after a rate-limited run)
./gradlew publishCdn -PtoolArgs="repair=true"

# Preview without contacting the CDN
./gradlew publishCdn -PtoolArgs="revs=241 dryRun=true"

# One asset kind only
./gradlew publishCdn -PtoolArgs="revs=240,241 kinds=sprites,textures"

# Models when the raw cache is no longer on disk — fetch it from OpenRS2 first
./gradlew publishCdn -PtoolArgs="revs=241 kinds=models downloadCache=true"
```

| Flag | Meaning |
|------|---------|
| `revs=` / `from=` `to=` | which published revisions (default: all of them) |
| `kinds=` | `sprites`, `textures`, `models` (default: all three) |
| `repair=true` | list the CDN prefix first, upload only absent objects |
| `dryRun=true` | report what would be uploaded, contact nothing |
| `downloadCache=true` | download the raw cache when models are requested and it is absent |

Sprite and texture bytes come from PostgreSQL, so those kinds never need a cache on disk. Raw model
`.dat` meshes are not stored in the database by design — they are large and immutable — so model
uploads read the revision's raw cache, which `downloadCache=true` will fetch.

Ingestion runs the same publisher after publishing a revision, so this tool is only needed for
backfill, repair, or a bucket change.
