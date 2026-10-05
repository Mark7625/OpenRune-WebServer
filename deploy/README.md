# Deployment

`.github/workflows/release.yml` builds the shadow JAR and deploys it over SSH, following the same
pattern as the 117HD discord bot that shares the box: GitHub release, then `scp` + `systemd`.

Triggered by a push to `production`, a `v*` tag, or manually.

## What ends up on the server

```
/opt/openrune-webserver/
  openrune-server.jar      the API, shared by every instance
  openrune.env             shared secrets + config, chmod 600
  instances/<name>.env     per-instance game, environment, port, heap
  ingest.sh                the Wednesday job
  logs/ingest-*.log        one per ingest run, pruned after 30 days
/etc/cloudflared/
  config.yml               hostname -> local port
  <tunnel-id>.json         tunnel credentials
```

| unit | what it does |
|------|--------------|
| `openrune-webserver@<name>` | one API instance, `Restart=always` |
| `openrune-ingest@<name>.timer` | fires hourly on Wednesdays, 11:00–17:00 |
| `openrune-ingest@<name>.service` | one-shot, runs `ingest.sh <name>` |
| `openrune-backfill@<name>` | operator-started import of older revisions |
| `cloudflared` | the tunnel fronting every instance |

## Backfilling older revisions

Run the **Backfill revisions** workflow (manual dispatch). Give it an instance plus either an
explicit `revs` list or a `from`/`to` range:

| input | example |
|-------|---------|
| instance | `osrs` |
| revs | `238,237,236` |
| from / to | `200` / `239` |

The workflow starts `openrune-backfill@<instance>` and returns — the import runs for hours on the
box, not in the Actions runner.

**Order and interruption.** Revisions are imported **newest first**, and anything already published
is skipped. Between each one the tool asks OpenRS2 whether a cache newer than the latest published
revision has appeared; if so it imports that first and then resumes the queue. The check is between
revisions, not during one: an import either publishes or it does not, so abandoning one half way
would throw the work away for nothing. Worst case the live update waits for the revision in flight.

## CDN uploads: only what changed

Assets are uploaded **at the revision their content changed in**, not at every revision. Between
240 and 241 that is 17 sprites of ~8,560 and 3 models of ~62,000 — so a weekly publish moves about
20 objects instead of 70,000.

`sprites.zip` and `textures.zip` follow the same rule: a zip of an unchanged set is byte for byte
the previous revision's, so it is not re-sent. They are rebuilt whenever the set changes at all —
any addition, change *or removal*, since a removal leaves no new version row. `textures.zip` also
rebuilds when sprites move, because it embeds sprite images.

This works because nothing guesses a CDN key from the revision being viewed. The API resolves it:

* `/sprites?id=&rev=` and `/diff/sprite/{id}` look up the revision the sprite last changed in.
* `/models/{id}/dat?rev=` redirects to the mesh's source revision; `/models/*` payloads already
  carry a `dat` URL resolved the same way.

Callers that already know the source revision (the diff views) still go straight to the CDN with no
hop. The resolution is a single indexed lookup of `entity_version.valid_from`.

Full-size sprite requests are **redirected to the CDN** rather than served from PostgreSQL, keeping
that traffic off the API and the database. This assumes a revision's assets are actually up there,
so before the CDN has been filled run:

```bash
./gradlew publishCdn -PtoolArgs="from=1 to=241 downloadCache=true"
```

Set `OPENRUNE_CDN_REDIRECT_SPRITES=false` to serve from PostgreSQL meanwhile — the database always
has every sprite, so that is the safe setting while backfilling the CDN.

To rebuild a revision's prefix in full, ignoring the changed-only rule:
`./gradlew publishCdn -PtoolArgs="revs=241 uploadUnchanged=true"`.

**CDN uploads come last.** A revision's sprites, textures and models are thousands of small objects;
uploading them between every import would dominate a long backfill. So a backfill imports everything
first and uploads assets once at the end, newest revision first. The data is queryable the moment
each revision publishes — only the images lag. A revision released mid-backfill is the exception:
it is what people are looking at, so its assets go up immediately.

For a normal single-revision ingest nothing changed: the upload was already the final step, and it
already swallows its own failures, so a CDN outage leaves the revision published and serving.

**Progress** is in the `backfill` table, reported on `/admin/overview` and drawn on the ingestion
page: how many are done, how many queued, which are next, a note when it has stepped aside for a new
release, and a note while it is uploading assets at the end. Stopping the unit stops it cleanly after
the current revision; the queue in the database records exactly where it got to.

```bash
journalctl -u openrune-backfill@osrs -f
systemctl stop openrune-backfill@osrs      # finishes the current revision, then stops
```

## Instances

The workflow's `INSTANCES` variable is the single source of truth — one line of
`name|game|environment|port|openrs2 cache id|api heap|ingest heap`:

```yaml
INSTANCES: |
  osrs|OLDSCHOOL|LIVE|8090|2727|2G|6G
  rs3|RUNESCAPE3|LIVE|8091|-1|2G|6G
```

Adding a row creates its env file, service, ingest timer and health check. Add the matching
hostname to `deploy/cloudflared-config.yml` and it is reachable. Instances share the database and
the jar and nothing else; each is a separate process with its own port and heap.

## Cloudflare Tunnel

Nothing is published directly — no API port is open to the internet and there is no inbound
firewall rule. `cloudflared` holds an outbound connection to Cloudflare and routes each hostname
to a local port:

| hostname | origin |
|----------|--------|
| `osrs.openrune.dev` | `http://localhost:8090` |
| `rs3.openrune.dev` | `http://localhost:8091` |

These are the hostnames the website already expects (`src/lib/cache-api-target.ts`).

One-off setup, on a machine logged into Cloudflare:

```bash
cloudflared tunnel login
cloudflared tunnel create openrune
# prints the tunnel id and writes ~/.cloudflared/<id>.json
cloudflared tunnel route dns openrune osrs.openrune.dev
cloudflared tunnel route dns openrune rs3.openrune.dev
```

Then set two GitHub secrets: `CLOUDFLARE_TUNNEL_ID` (the id) and
`CLOUDFLARE_TUNNEL_CREDENTIALS` (the whole contents of `<id>.json`). With them unset the deploy
still runs and just skips the tunnel, so the APIs can be brought up before DNS is ready.

**SSE through the tunnel.** `/sse` is a response that never ends, which proxies tend to buffer or
reap. The server sets `X-Accel-Buffering: no` and sends a `: ping` comment every 25 seconds —
comfortably inside Cloudflare's ~100s idle timeout — and the tunnel config sets a matching
`tcpKeepAlive`. Without the heartbeat a quiet stream is dropped roughly every two minutes.

## Required GitHub secrets

`SSH_HOST`, `SSH_USER`, `SSH_PRIVATE_KEY`, `SSH_PORT` (same values the bot uses), plus
`OPENRUNE_DB_PASSWORD`, `DISCORD_WEBHOOK_URL`, `OPENRUNE_ADMIN_TOKEN`, `R2_ACCOUNT_ID`,
`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `CLOUDFLARE_TUNNEL_ID`,
`CLOUDFLARE_TUNNEL_CREDENTIALS`.

## The ingest job

`ingest.sh` runs `IngestMain` with `new=true`: it asks OpenRS2 for builds newer than the latest
published revision, and ingests whatever it finds. No new cache is a no-op that exits 0, which is
the normal outcome for most of the seven firings.

**The API is never stopped or restarted by it.** Ingestion runs in its own process with
`OPENRUNE_INGEST_ENABLED=false` on the API, holds a `pg_advisory_lock` so two runs cannot overlap,
and publishes nothing unless the whole revision imported cleanly. The API only reads published
revisions, and picks a new one up within a second via `publish_stamp` — so there is nothing to
restart after a successful import, and nothing to recover after a failed one.

On failure the script posts the exit code and the last 20 log lines to the Discord webhook and
exits 0, so the timer unit stays healthy and the next week's run is unaffected.

## First deploy

The first run has no published revision, so the API serves `/status` and little else. Seed the
base revision by hand — it is a long import and should not be left to a timer slot:

```bash
cd /opt/openrune-webserver
set -a; . ./openrune.env; set +a
/usr/lib/jvm/java-17-openjdk-amd64/bin/java -Xmx8G -cp openrune-server.jar \
  dev.openrune.tools.IngestMainKt revs=1 game=OLDSCHOOL env=LIVE
```

Then let the Wednesday timer take it from there, or pass explicit `revs=` to backfill.

## Checking on it

```bash
systemctl status 'openrune-webserver@*'
systemctl list-timers 'openrune-ingest@*'
journalctl -u openrune-ingest@osrs -n 100
journalctl -u cloudflared -n 50
ls -t /opt/openrune-webserver/logs | head

# force a run without waiting for Wednesday
systemctl start openrune-ingest@osrs.service

# is the tunnel actually serving?
curl -sS https://osrs.openrune.dev/status
curl -sN https://osrs.openrune.dev/sse | head -5   # expect a STATUS event then `: ping`
```
