#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-harness-policy.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/check-harness-policy.py --repo .
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-pr-policy.py
exec ./gradlew check "$@"
