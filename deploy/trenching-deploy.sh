#!/usr/bin/env bash
# The single copy of the release logic. Installed on the server as /usr/local/bin/trenching-deploy and run as root,
# by deploy.ps1 (through sudo) and by the deploy key's forced command (trenching-deploy-ssh, through sudo).
#
#   trenching-deploy deploy <sha40> <sha256> [--no-auto-rollback] [--file PATH]
#   trenching-deploy rollback
#
# deploy reads the jar from --file (removed afterwards) or from stdin, verifies its SHA-256 and zip header, installs it
# as releases/<sha40>.jar, repoints current.jar, restarts the service and waits for health. If the new release does not
# become healthy it goes back to the previous one, unless --no-auto-rollback is given. Exit codes: 0 done, 1 failed,
# 2 bad usage.
set -euo pipefail

readonly BASE=/opt/trenching-bot
readonly RELEASES="$BASE/releases"
readonly PREVIOUS_FILE="$BASE/previous"
readonly LOCK_FILE="$BASE/.deploy.lock"
readonly SERVICE=trenching-bot
readonly BACKUP_SERVICE=trenching-bot-backup.service
readonly HEALTH_URL=http://127.0.0.1:8081/actuator/health
readonly OWNER=trenching
readonly KEEP_RELEASES=5
readonly HEALTH_TIMEOUT_SECONDS=180
readonly BACKUP_WAIT_SECONDS=900
readonly BACKUP_POLL_SECONDS=10
readonly MAX_JAR_BYTES=$((300 * 1024 * 1024))
readonly ZIP_MAGIC_HEX=504b0304

upload_file=""
remove_upload=false
staged_jar=""
jar_path=""

usage() {
  cat >&2 <<'EOF'
Usage:
  trenching-deploy deploy <sha40> <sha256> [--no-auto-rollback] [--file PATH]
  trenching-deploy rollback
EOF
  exit 2
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

cleanup() {
  [ -z "$staged_jar" ] || rm -f -- "$staged_jar"
  if [ "$remove_upload" = true ] && [ -n "$upload_file" ]; then
    rm -f -- "$upload_file"
  fi
}

is_healthy() {
  curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null | grep -Eq '"status" ?: ?"UP"'
}

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if is_healthy; then
      echo "Health is UP."
      return 0
    fi
    sleep 3
  done
  echo "Health did not reach UP within ${HEALTH_TIMEOUT_SECONDS} s. Last 80 journal lines:"
  journalctl -u "$SERVICE" -n 80 --no-pager || true
  return 1
}

point_current() {
  ln -sfn "$1" "$BASE/current.jar.next"
  mv -T "$BASE/current.jar.next" "$BASE/current.jar"
}

restart_and_wait() {
  systemctl restart "$SERVICE"
  wait_healthy
}

current_release() {
  readlink -e "$BASE/current.jar" 2>/dev/null || true
}

# Prints a release path only if it is a jar under releases/ that still exists and is not $2.
valid_release() {
  local candidate="$1" excluded="${2:-}"
  case "$candidate" in
    "$RELEASES"/*.jar) ;;
    *) return 1 ;;
  esac
  [ -f "$candidate" ] && [ "$candidate" != "$excluded" ]
}

acquire_lock() {
  exec 9>"$LOCK_FILE"
  flock -n 9 || die "another deploy is running (lock $LOCK_FILE is held)."
}

wait_for_backup() {
  local deadline=$((SECONDS + BACKUP_WAIT_SECONDS))
  while systemctl is-active --quiet "$BACKUP_SERVICE"; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      die "the backup is still running after $((BACKUP_WAIT_SECONDS / 60)) minutes; not restarting the service."
    fi
    echo "The backup is running; waiting ${BACKUP_POLL_SECONDS} s before restarting the service ..."
    sleep "$BACKUP_POLL_SECONDS"
  done
}

prune_releases() {
  local current previous jar
  current="$(readlink -f "$BASE/current.jar")"
  previous="$(cat "$PREVIOUS_FILE" 2>/dev/null || true)"
  { ls -1t "$RELEASES"/*.jar 2>/dev/null || true; } | tail -n "+$((KEEP_RELEASES + 1))" | while IFS= read -r jar; do
    if [ "$jar" != "$current" ] && [ "$jar" != "$previous" ]; then
      rm -f -- "$jar"
      echo "Removed old release $jar."
    fi
  done
}

drain_stdin() {
  if [ -z "$upload_file" ] && [ ! -t 0 ]; then
    cat > /dev/null || true
  fi
}

# Sets jar_path to the jar to deploy: the --file upload, or stdin staged into a temp file under $BASE.
stage_jar() {
  if [ -n "$upload_file" ]; then
    [ -f "$upload_file" ] || die "jar file $upload_file does not exist."
    jar_path="$upload_file"
    return 0
  fi
  [ ! -t 0 ] || die "no --file given and stdin is a terminal; pipe the jar into stdin."
  staged_jar="$(mktemp "$BASE/.upload.XXXXXX")"
  head -c "$((MAX_JAR_BYTES + 1))" > "$staged_jar"
  if [ "$(stat -c %s "$staged_jar")" -gt "$MAX_JAR_BYTES" ]; then
    die "the jar on stdin is larger than $((MAX_JAR_BYTES / 1024 / 1024)) MB."
  fi
  jar_path="$staged_jar"
}

verify_jar() {
  local jar="$1" expected_sha256="$2" actual magic
  actual="$(sha256sum "$jar" | cut -d' ' -f1)"
  [ "$actual" = "$expected_sha256" ] || die "SHA-256 mismatch: expected $expected_sha256, got $actual."
  magic="$(head -c 4 "$jar" | od -An -tx1 | tr -d ' \n')"
  [ "$magic" = "$ZIP_MAGIC_HEX" ] || die "the file is not a jar (missing zip header)."
}

# Rolls back to the release recorded before this deploy, then restores the previous-pointer to what it was.
auto_rollback() {
  local failed_release="$1" target="$2" original_previous="$3"
  if ! valid_release "$target" "$failed_release"; then
    echo "No previous release to roll back to; $failed_release stays current."
    return 1
  fi
  echo "Rolling back to $target ..."
  point_current "$target"
  if [ -n "$original_previous" ]; then
    printf '%s\n' "$original_previous" > "$PREVIOUS_FILE"
  else
    rm -f -- "$PREVIOUS_FILE"
  fi
  if restart_and_wait; then
    echo "Rolled back to $target."
  else
    echo "Rollback to $target also failed; the service needs a manual look."
  fi
  return 0
}

deploy_release() {
  local sha="$1" expected_sha256="$2" auto_rollback_enabled="$3"
  local release="$RELEASES/$sha.jar" current original_previous

  acquire_lock
  current="$(current_release)"
  if [ "$current" = "$release" ] && is_healthy; then
    # A CI rerun still pipes the jar; read it to the end so the sender does not fail on a broken pipe.
    drain_stdin
    echo "Release $sha is already current."
    return 0
  fi

  stage_jar
  verify_jar "$jar_path" "$expected_sha256"
  wait_for_backup

  original_previous="$(cat "$PREVIOUS_FILE" 2>/dev/null || true)"
  install -m 0644 -o "$OWNER" -g "$OWNER" "$jar_path" "$release.tmp"
  mv -f -- "$release.tmp" "$release"
  if [ -n "$current" ] && [ "$current" != "$release" ]; then
    printf '%s\n' "$current" > "$PREVIOUS_FILE"
  fi
  point_current "$release"

  if restart_and_wait; then
    prune_releases
    echo "Deployed $sha."
    return 0
  fi
  if [ "$auto_rollback_enabled" = true ]; then
    auto_rollback "$release" "$(cat "$PREVIOUS_FILE" 2>/dev/null || true)" "$original_previous" || true
  fi
  return 1
}

rollback_release() {
  local target current
  acquire_lock
  [ -s "$PREVIOUS_FILE" ] || die "no previous release to roll back to."
  target="$(cat "$PREVIOUS_FILE")"
  valid_release "$target" || die "previous release $target is missing or not under $RELEASES."
  wait_for_backup
  current="$(current_release)"
  point_current "$target"
  if [ -n "$current" ]; then
    printf '%s\n' "$current" > "$PREVIOUS_FILE"
  fi
  restart_and_wait || return 1
  echo "Rolled back to $target."
}

parse_and_run() {
  local mode="${1:-}"
  if [ "$#" -gt 0 ]; then shift; fi
  case "$mode" in
    deploy)
      local sha="${1:-}" sha256="${2:-}" auto=true
      [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || usage
      [[ "$sha256" =~ ^[0-9a-f]{64}$ ]] || usage
      shift 2
      while [ "$#" -gt 0 ]; do
        case "$1" in
          --no-auto-rollback) auto=false; shift ;;
          --file)
            [ "$#" -ge 2 ] && [ -z "$upload_file" ] || usage
            upload_file="$2"
            remove_upload=true
            shift 2
            ;;
          *) usage ;;
        esac
      done
      require_root
      deploy_release "$sha" "$sha256" "$auto"
      ;;
    rollback)
      [ "$#" -eq 0 ] || usage
      require_root
      rollback_release
      ;;
    *) usage ;;
  esac
}

require_root() {
  [ "$EUID" -eq 0 ] || die "must run as root (use sudo)."
}

trap cleanup EXIT
parse_and_run "$@"
