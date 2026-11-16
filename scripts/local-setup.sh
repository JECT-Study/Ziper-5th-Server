#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Exclusive creation keeps repeated setup from replacing existing credentials.
if [ ! -e .env ]; then
  umask 077
  python3 - <<'PY'
import base64
import pathlib
import secrets
text = pathlib.Path('.env.example').read_text()
for name in ('ADMIN', 'APP', 'MIGRATION'):
    text = text.replace(f'REPLACE_WITH_RANDOM_{name}_PASSWORD', secrets.token_hex(32))
text = text.replace('REPLACE_WITH_BASE64_ENCODED_32_RANDOM_BYTES', base64.b64encode(secrets.token_bytes(32)).decode())
text = text.replace('REPLACE_WITH_BASE64_ENCODED_EMAIL_CODE_SECRET', base64.b64encode(secrets.token_bytes(32)).decode())
try:
    with open('.env', 'x') as target:
        target.write(text)
except FileExistsError:
    pass
PY
fi
git config core.hooksPath .githooks
printf '%s\n' 'Local .env is ready; Git quality hooks installed.'
