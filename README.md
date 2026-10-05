# OpenRune Web Server

A fast and efficient Kotlin web server for displaying information from RuneScape cache files.

## Features

- Fast Ktor-based web server using Netty
- Cache manifest system with MD5 checksum tracking
- Automatic cache change detection
- Status endpoint available during boot

## Usage

Run the server with the following arguments:

```
./gradlew run --args="<revision> <game> <environment> <port>"
```

### Arguments

- `revision` (optional): Cache revision number (default: -1)
- `game` (optional): Game type - `OLDSCHOOL` or `RUNESCAPE3` (default: `OLDSCHOOL`)
- `environment` (optional): Environment - `LIVE`, `BETA`, or `TEST` (default: `LIVE`)
- `port` (optional): Network port (default: 8090)
- `navDisplayOverrides` (optional): Comma/semicolon-separated `key=value` list for nav display-name overrides.

### Nav Display Name Overrides

You can override sidebar labels returned by `/cache/nav` and `/cache/gameval/groups`.

Supported input formats:

- CLI arg 5: `worldentity=World Entities;worldmaparea=World Map Area;spotanim=SpotAnim;inv=Inventories`
- Env var `OPENRUNE_NAV_DISPLAY_OVERRIDES` with the same format.

Examples:

```bash
# CLI argument
./gradlew run --args="237 OLDSCHOOL LIVE 8090 worldentity=World Entities;worldmaparea=World Map Area;spotanim=SpotAnim;inv=Inventories"

# Environment variable
OPENRUNE_NAV_DISPLAY_OVERRIDES="worldentity=World Entities;worldmaparea=World Map Area;spotanim=SpotAnim;inv=Inventories" ./gradlew run --args="237 OLDSCHOOL LIVE 8090"
```

### Examples

```bash
# Default settings
./gradlew run

# Custom revision and port
./gradlew run --args="227 8091"

# Full configuration
./gradlew run --args="227 OLDSCHOOL LIVE 8090"
```

## Endpoints

### GET /status

Returns the current server status and configuration.

**Response:**
```json
{
  "status": "BOOTING" | "LIVE" | "ERROR",
  "game": "OLDSCHOOL" | "RUNESCAPE3",
  "revision": 227,
  "environment": "LIVE" | "BETA" | "TEST",
  "port": 8090
}
```

The status endpoint is available immediately when the server starts, even during cache loading.

## PostgreSQL mode

Set `OPENRUNE_DATABASE_URL` (see `.env.example`) and the server stores every revision in
PostgreSQL instead of `.bin` files. Design and rationale: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md);
measurements: [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

* Revisions are discovered on OpenRS2, ingested in the background and published atomically.
  The site keeps serving published revisions while a new one is processed; a failed revision
  is rolled back and retried, never half-visible.
* `/status.revision` is the latest **published** revision. `/admin/*` exposes ingestion state,
  runs, metrics, database and memory information (token: `OPENRUNE_ADMIN_TOKEN`; without a
  token only loopback clients are allowed). The website shows it under Tools → Ingestion.
* All existing endpoints keep their response shapes. List endpoints additionally return
  `nextCursor` and accept `after=<id>` for keyset paging.

### Tools

```bash
# Ingest specific revisions from OpenRS2 (download, decode, import, validate, publish)
./gradlew ingestRevisions -PtoolArgs="revs=240,241"

# Import existing legacy .bin history without re-downloading caches
./gradlew importLegacyBins -PtoolArgs="from=1 to=241"

# Re-upload / repair raw assets on the CDN for already-published revisions
./gradlew publishCdn -PtoolArgs="revs=241"
./gradlew publishCdn -PtoolArgs="repair=true"              # only what is missing
./gradlew publishCdn -PtoolArgs="revs=241 kinds=models downloadCache=true"

# Prove PostgreSQL state and diffs match the legacy .bin system
./gradlew validateLegacy -PtoolArgs="revs=1,240,241"

# Benchmark legacy vs PostgreSQL (run modes in separate JVMs for clean memory numbers)
./gradlew benchmark -PtoolArgs="mode=legacy revs=1,240,241"
./gradlew benchmark -PtoolArgs="mode=postgres revs=1,240,241"

# RS3-sized synthetic stream: ingestion throughput and query latency at scale
./gradlew syntheticScale -PtoolArgs="revs=50 types=20 entities=100000"
```

Tests run against an embedded PostgreSQL (`./gradlew test`). Gradle itself must run on a
JDK up to 21 (`-Dorg.gradle.java.home=...`); the compiled target stays Java 11.

## Project Structure

```
src/main/kotlin/dev/openrune/
├── Main.kt                    # Entry point; PostgreSQL mode when OPENRUNE_DATABASE_URL is set
├── App.kt / Platform.kt       # Wiring: database, registry, pipeline, worker, HTTP
├── api/                       # Ktor routes (thin), admin endpoints, zip jobs
├── query/                     # Read side: revision catalog, entity + diff queries
├── store/                     # Game/type registry, revision rows, version writer (COPY + merge)
├── ingest/                    # Discovery, raw cache download, pipeline, OSRS and legacy sources
├── db/                        # Connection pools, migrations, JDBC helpers
├── model/                     # Game, entity types, snapshots, canonical JSON, hashing
├── tools/                     # ingest / import / validate / benchmark entry points
├── cache/                     # Cache decoding helpers and the legacy .bin format
└── server/                    # Legacy .bin server (kept until the migration is complete)
```

## Build

```bash
./gradlew build
```

## License

MIT






