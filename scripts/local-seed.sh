#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
[ "$#" -eq 0 ] || { echo 'No arguments accepted; this script only seeds the local Compose database.' >&2; exit 1; }
[ -f .env ] || { echo 'Run scripts/local-setup.sh first.' >&2; exit 1; }
# Fix the file and project explicitly; never accept an external database URL.
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES
docker compose --env-file .env -f compose.yml -p ziper-local exec -T db \
  psql -U ziper_admin -d ziper -v ON_ERROR_STOP=1 --single-transaction < db/seed/local.sql
