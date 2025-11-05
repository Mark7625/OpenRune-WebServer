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

## Cache Manifest System

On first load, the server will:
1. Scan all cache files and calculate MD5 checksums
2. Save a manifest file (`cache-manifest.json`) with all file hashes
3. On subsequent loads, compare against the existing manifest
4. If changes are detected, update the manifest and write update data to `update-data/`

## Project Structure

```
src/main/kotlin/dev/openrune/
├── Main.kt                    # Entry point with argument parsing
├── ServerConfig.kt            # Server configuration data class
├── GameType.kt                # Game type enumeration
├── CacheEnvironment.kt        # Environment type enumeration
├── cache/
│   └── CacheManager.kt        # Cache manifest and MD5 management
└── server/
    └── WebServer.kt           # Ktor web server implementation
```

## Build

```bash
./gradlew build
```

## License

MIT






