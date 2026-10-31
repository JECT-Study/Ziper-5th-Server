#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Dedicated project and random ports/credentials protect an existing local setup.
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES
unset POSTGRES_PASSWORD DB_PASSWORD MIGRATION_PASSWORD JWT_SECRET_BASE64
unset SENTRY_ENABLED SENTRY_DSN SENTRY_ENVIRONMENT SENTRY_RELEASE SENTRY_TRACES_SAMPLE_RATE
work=$(mktemp -d)
project="ziper-smoke-$(date +%s)-$$"
export ZIPER_ENV_FILE="$work/local.env"
python3 - "$ZIPER_ENV_FILE" <<'PY'
import base64, pathlib, secrets, sys
text = pathlib.Path('.env.example').read_text()
for name in ('ADMIN', 'APP', 'MIGRATION'):
    text = text.replace(f'REPLACE_WITH_RANDOM_{name}_PASSWORD', secrets.token_hex(32))
text = text.replace('REPLACE_WITH_BASE64_ENCODED_32_RANDOM_BYTES', base64.b64encode(secrets.token_bytes(32)).decode())
pathlib.Path(sys.argv[1]).write_text(text)
PY
export APP_PORT=0 DB_PORT=0
compose() { docker compose --env-file "$ZIPER_ENV_FILE" -f compose.yml -p "$project" "$@"; }
cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then
    mkdir -p build/reports/container-smoke
    compose logs --no-color > build/reports/container-smoke/compose.log 2>&1 || true
    cat build/reports/container-smoke/compose.log >&2
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work"
  exit "$result"
}
trap cleanup EXIT
compose up --build --wait --wait-timeout 240
address=$(compose port app 8080)
base="http://$address"
probe() {
  local path=$1 expected=$2 code
  code=$(curl --silent --show-error --max-time 15 -D "$work/headers" -o /dev/null -w '%{http_code}' "$base$path") || return 1
  [ "$code" = "$expected" ] || return 1
  tr -d '\r' < "$work/headers" | grep -Eiq '^cache-control:.*no-store' || return 1
}
await_probe() {
  local path=$1 expected=$2
  for ((i=0; i<60; i++)); do
    if probe "$path" "$expected"; then return 0; fi
    sleep 2
  done
  echo "Failed health contract: $path expected $expected with Cache-Control no-store" >&2
  return 1
}
await_probe /livez 200
await_probe /readyz 200
compose stop db
await_probe /readyz 503
probe /livez 200
compose start db
await_probe /readyz 200
probe /livez 200
printf '%s\n' 'Container health, database outage, and recovery checks passed.'
