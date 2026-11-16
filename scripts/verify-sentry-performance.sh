#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ "$#" -ne 0 ]; then
  echo "This command takes no arguments; supply SENTRY_DSN privately in the environment." >&2
  exit 2
fi
: "${SENTRY_DSN:?Supply SENTRY_DSN privately in the environment}"
export SENTRY_PERFORMANCE_LIVE_VERIFICATION=true
export SENTRY_VERIFICATION_RUN="${SENTRY_VERIFICATION_RUN:-$(python3 -c 'import uuid; print(uuid.uuid4())')}"
export SENTRY_RELEASE="${SENTRY_RELEASE:-local-performance}"
export SENTRY_PROFILE_SESSION_SAMPLE_RATE="${SENTRY_PROFILE_SESSION_SAMPLE_RATE:-1.0}"
./gradlew test --tests '*SentryTracingLiveVerificationTest' --rerun-tasks --console=plain
python3 - <<'PY'
import xml.etree.ElementTree as ET

path = 'build/test-results/test/TEST-com.ject.ziper.global.observability.SentryTracingLiveVerificationTest.xml'
report = ET.parse(path).getroot()
for node in report.iter('system-out'):
    for line in (node.text or '').splitlines():
        if line.startswith('Sentry performance '):
            print(line)
PY
