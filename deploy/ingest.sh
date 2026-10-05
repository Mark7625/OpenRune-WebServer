#!/usr/bin/env bash
#
# Asks OpenRS2 for a cache newer than what is published for one instance and ingests it.
#
#   ./ingest.sh [instance]      # defaults to $OPENRUNE_INSTANCE, else osrs
#
# Run by openrune-ingest@<instance>.timer on Wednesdays. Three things matter here:
#
#  * Nothing new is success, not failure — the timer fires hourly across the window and most
#    firings have nothing to do.
#  * A failed ingest must not touch the API. It does not: ingestion runs in this separate process,
#    publishes nothing unless the whole revision imported cleanly, and the API only ever reads
#    published revisions.
#  * The API needs no restart afterwards. It re-reads `publish_stamp` at most once a second, and
#    reports live progress from the `ingest_run` row this process writes.
#
set -uo pipefail

DEPLOY_DIR="${OPENRUNE_DEPLOY_DIR:-/opt/openrune-webserver}"
INSTANCE="${1:-${OPENRUNE_INSTANCE:-osrs}}"

# Sourced explicitly rather than relying on systemd, so a manual run behaves identically.
# Read as data, never sourced: a secret containing `|` or `;` would otherwise be run as a command.
# shellcheck disable=SC1090,SC1091
. "${DEPLOY_DIR}/env-load.sh"
load_env_file "${DEPLOY_DIR}/openrune.env"
load_env_file "${DEPLOY_DIR}/instances/${INSTANCE}.env"

JAVA="${OPENRUNE_JAVA:-/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
JAR="${DEPLOY_DIR}/openrune-server.jar"
PORT="${OPENRUNE_PORT:-8090}"
GAME="${OPENRUNE_GAME:-OLDSCHOOL}"
ENVIRONMENT="${OPENRUNE_ENVIRONMENT:-LIVE}"
HEAP="${OPENRUNE_INGEST_HEAP:-6G}"
LOG_DIR="${DEPLOY_DIR}/logs"
LOG="${LOG_DIR}/ingest-${INSTANCE}-$(date -u +%Y%m%dT%H%M%SZ).log"

mkdir -p "${LOG_DIR}"

# Discord rejects anything that is not valid JSON, so the message is escaped with jq rather than
# interpolated — ingest logs contain quotes, newlines and backslashes.
notify() {
  [ -n "${DISCORD_WEBHOOK_URL:-}" ] || return 0
  printf '%s' "$1" | jq -Rs '{content: .}' |
    curl -sS -m 15 -H 'Content-Type: application/json' -d @- "${DISCORD_WEBHOOK_URL}" >/dev/null || true
}

published_revision() {
  curl -sS -m 10 "http://127.0.0.1:${PORT}/status" 2>/dev/null |
    grep -o '"revision":[0-9]*' | cut -d: -f2 || true
}

before="$(published_revision)"

"${JAVA}" -Xmx"${HEAP}" -cp "${JAR}" dev.openrune.tools.IngestMainKt \
  new=true "game=${GAME}" "env=${ENVIRONMENT}" >>"${LOG}" 2>&1
status=$?

if [ "${status}" -ne 0 ]; then
  notify "$(printf ':x: **OpenRune ingest failed** — `%s` (exit %s)\n\nThe API is untouched and still serving revision %s.\nLog: `%s`\n```\n%s\n```' \
    "${INSTANCE}" "${status}" "${before:-unknown}" "${LOG}" "$(tail -n 20 "${LOG}" | tail -c 1200)")"
  # Exit 0 so the timer unit does not go into a failed state; the webhook is the alert channel.
  exit 0
fi

after="$(published_revision)"
if [ -n "${after}" ] && [ "${before:-}" != "${after}" ]; then
  notify "$(printf ':white_check_mark: **OpenRune `%s` ingested revision %s** (was %s) — live now, no restart needed.' \
    "${INSTANCE}" "${after}" "${before:-none}")"
fi

find "${LOG_DIR}" -name 'ingest-*.log' -mtime +30 -delete 2>/dev/null || true
