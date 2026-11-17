#!/usr/bin/env bash
# Forced command of the GitHub deploy key. Installed as /usr/local/bin/trenching-deploy-ssh and run as user "deploy".
# It accepts exactly "deploy <40 hex> <64 hex>" or "rollback" and hands them to the root release script through sudo.
# stdin (the jar) passes through. CI deploys always auto-roll back, so --file and --no-auto-rollback are never passed.
set -euo pipefail

readonly DEPLOY_BIN=/usr/local/bin/trenching-deploy
readonly ALLOWED='^(deploy [0-9a-f]{40} [0-9a-f]{64}|rollback)$'

command_line="${SSH_ORIGINAL_COMMAND:-}"

if [[ ! "$command_line" =~ $ALLOWED ]]; then
  echo "Refused: only 'deploy <sha40> <sha256>' and 'rollback' are allowed." >&2
  exit 2
fi

read -r -a args <<< "$command_line"
exec sudo -n "$DEPLOY_BIN" "${args[@]}"
