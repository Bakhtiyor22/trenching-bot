#!/usr/bin/env bash
# Nightly PostgreSQL dump, run by trenching-bot-backup.service as root.
# DATABASE_USERNAME comes from the service's EnvironmentFile.
set -euo pipefail

readonly CONTAINER=trenching-bot-postgres
readonly DATABASE=trenching_bot
readonly BACKUP_DIR=/data/backups
readonly KEEP=7
readonly MIN_FREE_BYTES=$((5 * 1024 * 1024 * 1024))

: "${DATABASE_USERNAME:?DATABASE_USERNAME is not set}"

umask 077
cd "$BACKUP_DIR"

newest="$(ls -1t trenching_bot-*.dump 2>/dev/null | head -n 1 || true)"
newest_size=0
if [ -n "$newest" ]; then
  newest_size="$(stat -c %s "$newest")"
fi

required=$((2 * newest_size))
if [ "$required" -lt "$MIN_FREE_BYTES" ]; then
  required=$MIN_FREE_BYTES
fi

free="$(df --output=avail -B1 "$BACKUP_DIR" | tail -n 1 | tr -d ' ')"
if [ "$free" -lt "$required" ]; then
  echo "ERROR: $free bytes free on $BACKUP_DIR, need $required. No dump taken, nothing deleted." >&2
  exit 1
fi

name="trenching_bot-$(date -u +%Y%m%d-%H%M%S).dump"
partial=".$name.partial"
trap 'rm -f -- "$partial"' EXIT

docker exec "$CONTAINER" pg_dump -U "$DATABASE_USERNAME" -d "$DATABASE" -Fc > "$partial"

if [ ! -s "$partial" ]; then
  echo "ERROR: the dump is empty." >&2
  exit 1
fi

if ! docker exec -i "$CONTAINER" pg_restore --list < "$partial" > /dev/null; then
  echo "ERROR: the dump is not a readable pg_dump archive; keeping nothing." >&2
  exit 1
fi

mv -- "$partial" "$name"
echo "Wrote $BACKUP_DIR/$name ($(stat -c %s "$name") bytes)."

ls -1t trenching_bot-*.dump | tail -n "+$((KEEP + 1))" | while read -r old; do
  rm -f -- "$old"
  echo "Removed old dump $old."
done
