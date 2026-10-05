#!/usr/bin/env bash
#
# Runs a backfill for one instance. The revision list is written by the backfill workflow to
# instances/<name>.backfill as the tool's own flags, e.g. `revs=238,237` or `from=200 to=239`.
#
#   ./backfill.sh [instance]
#
# The tool works newest first and checks OpenRS2 between revisions, so a cache released mid-run is
# imported ahead of the queue and the queue then resumes. Progress is in the `backfill` table, which
# the API reports on /admin/overview.
#
set -uo pipefail

DEPLOY_DIR="${OPENRUNE_DEPLOY_DIR:-/opt/openrune-webserver}"
INSTANCE="${1:-${OPENRUNE_INSTANCE:-osrs}}"

set -a
# shellcheck disable=SC1090,SC1091
. "${DEPLOY_DIR}/openrune.env"
# shellcheck disable=SC1090
. "${DEPLOY_DIR}/instances/${INSTANCE}.env"
set +a

ARGS_FILE="${DEPLOY_DIR}/instances/${INSTANCE}.backfill"
[ -f "${ARGS_FILE}" ] || { echo "No backfill request at ${ARGS_FILE}" >&2; exit 1; }
# shellcheck disable=SC2046
set -- $(cat "${ARGS_FILE}")

JAVA="${OPENRUNE_JAVA:-/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
JAR="${DEPLOY_DIR}/openrune-server.jar"
HEAP="${OPENRUNE_BACKFILL_HEAP:-${OPENRUNE_INGEST_HEAP:-8G}}"

notify() {
  [ -n "${DISCORD_WEBHOOK_URL:-}" ] || return 0
  printf '%s' "$1" | jq -Rs '{content: .}' |
    curl -sS -m 15 -H 'Content-Type: application/json' -d @- "${DISCORD_WEBHOOK_URL}" >/dev/null || true
}

notify "$(printf ':arrows_counterclockwise: **OpenRune backfill started** — `%s` (%s)' "${INSTANCE}" "$*")"

"${JAVA}" -Xmx"${HEAP}" -cp "${JAR}" dev.openrune.tools.BackfillMainKt \
  "game=${OPENRUNE_GAME}" "env=${OPENRUNE_ENVIRONMENT}" "$@"
status=$?

rm -f "${ARGS_FILE}"

if [ "${status}" -ne 0 ]; then
  notify "$(printf ':x: **OpenRune backfill ended with errors** — `%s` (exit %s). The API is unaffected; see `journalctl -u openrune-backfill@%s`.' \
    "${INSTANCE}" "${status}" "${INSTANCE}")"
else
  notify "$(printf ':white_check_mark: **OpenRune backfill finished** — `%s`.' "${INSTANCE}")"
fi
exit "${status}"
