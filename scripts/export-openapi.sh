#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ "$#" -gt 1 ]; then
  echo "Usage: $0 [output_dir]" >&2
  exit 2
fi
target="${1:-build/openapi}"
mkdir -p "$target"
export_dir="$(cd "$target" && pwd)"
OPENAPI_EXPORT_DIR="$export_dir" ./gradlew test --tests '*OpenApiSpecExportTest' --rerun-tasks --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 scripts/openapi_docs.py build-html --input-dir "$export_dir" --output "$export_dir/ziper-api-docs.html"
