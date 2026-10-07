#!/usr/bin/env bash
#
# Uploads already-imported revisions' assets to the CDN for one instance. The flags are written by
# the "Publish CDN" workflow to instances/<name>.publishcdn as the tool's own flags, e.g.
# `revs=225,226` or `from=225 to=241 repair=true`.
#
#   ./publish-cdn.sh [instance]
#
# Nothing is imported and no revision changes state: sprite and texture bytes come from PostgreSQL
# and model meshes from the raw cache, so this only re-reads what is already published. That is what
# makes it safe to run against a live API — and why it is a separate unit from the backfill, which
# is the thing that actually writes revisions.
#
# Progress goes to the `backfill` table's cdn_* columns and to an `ingest_run` row per revision, so
# the ingestion page shows which revision and how many of its files are up.
#
set -uo pipefail

DEPLOY_DIR="${OPENRUNE_DEPLOY_DIR:-/opt/openrune-webserver}"
INSTANCE="${1:-${OPENRUNE_INSTANCE:-osrs}}"

# Read as data, never sourced: a secret containing `|` or `;` would otherwise be run as a command.
# shellcheck disable=SC1090,SC1091
. "${DEPLOY_DIR}/env-load.sh"
load_env_file "${DEPLOY_DIR}/openrune.env"
load_env_file "${DEPLOY_DIR}/instances/${INSTANCE}.env"

ARGS_FILE="${DEPLOY_DIR}/instances/${INSTANCE}.publishcdn"
[ -f "${ARGS_FILE}" ] || { echo "No CDN publish request at ${ARGS_FILE}" >&2; exit 1; }
# shellcheck disable=SC2046
set -- $(cat "${ARGS_FILE}")

JAVA="${OPENRUNE_JAVA:-/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
JAR="${DEPLOY_DIR}/openrune-server.jar"
# Sprites for a revision are loaded as one map before they are sent, so this wants the same headroom
# an import does.
HEAP="${OPENRUNE_PUBLISHCDN_HEAP:-${OPENRUNE_BACKFILL_HEAP:-${OPENRUNE_INGEST_HEAP:-8G}}}"

notify() {
  [ -n "${DISCORD_WEBHOOK_URL:-}" ] || return 0
  printf '%s' "$1" | jq -Rs '{content: .}' |
    curl -sS -m 15 -H 'Content-Type: application/json' -d @- "${DISCORD_WEBHOOK_URL}" >/dev/null || true
}

notify "$(printf ':outbox_tray: **OpenRune CDN publish started** — `%s` (%s)' "${INSTANCE}" "$*")"

"${JAVA}" -Xmx"${HEAP}" -cp "${JAR}" dev.openrune.tools.PublishCdnMainKt \
  "game=${OPENRUNE_GAME}" "env=${OPENRUNE_ENVIRONMENT}" "$@"
status=$?

rm -f "${ARGS_FILE}"

if [ "${status}" -ne 0 ]; then
  notify "$(printf ':x: **OpenRune CDN publish ended with errors** — `%s` (exit %s). Published data is unaffected; see `journalctl -u openrune-publishcdn@%s`.' \
    "${INSTANCE}" "${status}" "${INSTANCE}")"
else
  notify "$(printf ':white_check_mark: **OpenRune CDN publish finished** — `%s`.' "${INSTANCE}")"
fi
exit "${status}"
