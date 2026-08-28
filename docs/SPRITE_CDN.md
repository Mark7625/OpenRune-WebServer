# Sprite CDN (CloudFront / S3 / Cloudflare R2)

Dump uploads PNGs + a zip whenever a revision is dumped.

## Layout

```
{osrs|rs3}/rev/{rev}/sprites/{id}.png
{osrs|rs3}/rev/{rev}/sprites.zip
{osrs|rs3}/rev/{rev}/textures.zip
{osrs|rs3}/rev/{rev}/models/{id}.dat
```

`textures.zip` holds `{textureId}.png` — each texture's `fileId` resolved against that revision's sprites. Zip only; textures have no per-id CDN objects.

`models/{id}.dat` is the raw mesh straight out of index 7, a full set per revision. Model *metadata* is not on the CDN — it lives in the revision `.bin` (`MDLM` trailer) and is served by `/models`; see [MODELS.md](MODELS.md).

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
| `OPENRUNE_SPRITES_IN_BIN` | `false` to omit PNG payloads from `.bin` (metadata kept) |

After the first dump with CDN enabled, set `OPENRUNE_SPRITES_IN_BIN=false` on subsequent dumps to shrink binaries.

## Website

Hardcoded to `https://cdn.openrune.dev` in `cache-api-client.ts` (`SPRITES_CDN_BASE`). No env vars.

Full-size sprites load from CDN; resized requests still hit `/sprites?width=&height=` (API fetches CDN/bin then resizes).

## Migrate existing `.bin` sprites

Upload PNGs already stored in local diffs (no re-dump required):

```bash
./gradlew migrateSpritesToCdn
./gradlew migrateSpritesToCdn -PcdnDryRun=true
./gradlew migrateSpritesToCdn -PcdnFrom=1 -PcdnTo=500 -PcdnSkipUnchanged=true
```

Requires CDN env (`.env` / `R2_*`). Reconstructs the full sprite set at each rev from base + deltas, then uploads `{game}/rev/{rev}/sprites/` + zip — same as dump-time publish.

### Repair partial uploads

If `sprites.zip` is fine but individual `{id}.png` objects are missing (common after R2 rate-limits / mid-run failures), use **repair** — lists the CDN prefix and only PUTs missing ids:

```bash
# Preview gaps for one rev
./gradlew migrateSpritesToCdn -PcdnFrom=239 -PcdnTo=239 -PcdnRepair=true -PcdnDryRun=true

# Fill missing PNGs for that rev (from .bin reconstruction)
./gradlew migrateSpritesToCdn -PcdnFrom=239 -PcdnTo=239 -PcdnRepair=true

# Repair a range (still walks earlier bins so the merged set is correct)
./gradlew migrateSpritesToCdn -PcdnFrom=1 -PcdnTo=300 -PcdnRepair=true
```

Notes:
- Uploads are **one PNG at a time** with a tongfei progress bar per rev (`rev N sprites`).
- Each object retries with backoff; a rev **fails** if any PNG still fails — no silent gaps.
- `skipUnchanged` is ignored while `repair=true` (unchanged revs can still have incomplete CDN folders).
- Prefer repair over full re-upload when zips already look correct.
