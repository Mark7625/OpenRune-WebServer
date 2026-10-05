#!/usr/bin/env bash
#
# Reads a KEY=VALUE env file into the environment *as data*.
#
# `. openrune.env` runs the file as a shell script, so a value containing `|`, `;`, `$`, `&` or a
# backtick is executed rather than assigned. Secrets are exactly the values likely to contain them:
# an admin token of `3q/%q#G7~U£m|nV581tu;5` ends the assignment at the pipe and tries to run the
# rest as a command, which fails the script with exit 127 before it does any work.
#
# systemd's `EnvironmentFile=` already reads this same file as data, which is why the services start
# correctly while anything sourcing the file did not. This closes that gap for the scripts.
#
# Usage:
#   . "${DEPLOY_DIR}/env-load.sh"
#   load_env_file "${DEPLOY_DIR}/openrune.env"
#
load_env_file() {
  local file="$1"
  local line key value
  [ -f "${file}" ] || { echo "No env file at ${file}" >&2; return 1; }
  # No `read -r ... <<<` or command substitution anywhere in here: every byte of the value has to
  # stay literal, including a trailing backslash or a leading `~`.
  while IFS= read -r line || [ -n "${line}" ]; do
    # Leading whitespace, blanks and comments. A `#` inside a value is data, not a comment, so only
    # a line that starts with one is skipped.
    line="${line#"${line%%[![:space:]]*}"}"
    case "${line}" in
      '' | '#'*) continue ;;
      *'='*) ;;
      *) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    # Strip one layer of surrounding quotes, matching what systemd does, so both readers of the file
    # agree on the value whether or not the writer quoted it.
    case "${value}" in
      \"*\") value="${value#\"}" ; value="${value%\"}" ;;
      \'*\') value="${value#\'}" ; value="${value%\'}" ;;
    esac
    export "${key}=${value}"
  done < "${file}"
}
