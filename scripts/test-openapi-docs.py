#!/usr/bin/env python3
"""Unit and local HTTP integration tests for scripts/openapi_docs.py."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location('openapi_docs', ROOT / 'scripts/openapi_docs.py')
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class OpenApiDocsTests(unittest.TestCase):
    def test_diff_endpoints_tracks_additions_and_removals_only(self):
        before = {
            'openapi': '3.1.0',
            'paths': {
                '/api/v1/users/me': {
                    'get': {'summary': 'before'},
                    'parameters': [{'name': 'X-Test', 'in': 'header'}],
                },
                '/api/v1/legacy': {'delete': {'summary': 'old'}},
            },
        }
        same_endpoints_schema_only = {
            'openapi': '3.1.0',
            'paths': {
                '/api/v1/users/me': {'get': {'summary': 'updated schema description'}},
                '/api/v1/legacy': {'delete': {'summary': 'old'}},
            },
        }
        unchanged = MOD.diff_endpoints(same_endpoints_schema_only, before_spec=before)
        self.assertFalse(unchanged['changed'])
        self.assertFalse(unchanged['should_publish'])
        self.assertEqual([], unchanged['added'])
        self.assertEqual([], unchanged['removed'])
        self.assertEqual(2, unchanged['total'])

        forced = MOD.diff_endpoints(same_endpoints_schema_only, before_spec=before, force=True)
        self.assertFalse(forced['changed'])
        self.assertTrue(forced['should_publish'])

        versioned_change = {
            'openapi': '3.1.0',
            'paths': {
                '/api/v1/users/me': {'get': {'summary': 'v1'}},
                '/api/v2/users/me': {'get': {'summary': 'v2'}},
            },
        }
        changed = MOD.diff_endpoints(versioned_change, before_spec=before)
        self.assertTrue(changed['changed'])
        self.assertTrue(changed['should_publish'])
        self.assertEqual(['GET /api/v2/users/me'], changed['added'])
        self.assertEqual(['DELETE /api/v1/legacy'], changed['removed'])

        baseline = MOD.diff_endpoints(versioned_change, before_spec=None)
        self.assertTrue(baseline['baseline'])
        self.assertTrue(baseline['should_publish'])
        self.assertEqual(['GET /api/v1/users/me', 'GET /api/v2/users/me'], baseline['added'])
        self.assertEqual([], baseline['removed'])

    def test_build_html_creates_self_contained_single_file_and_escapes_script_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            src = Path(directory) / 'raw'
            src.mkdir()
            (src / 'openapi.json').write_text(
                json.dumps({
                    'openapi': '3.1.0',
                    'info': {'title': 'Ziper API', 'version': 'v1'},
                    'paths': {'/api/v1/test': {'get': {'description': 'unsafe </script><script>alert(1)</script>'}}},
                }),
                encoding='utf-8',
            )
            (src / 'swagger-ui.css').write_text('.swagger-ui { color: #111; } /* </style> */', encoding='utf-8')
            (src / 'index.css').write_text('body { margin: 0; }', encoding='utf-8')
            (src / 'swagger-ui-bundle.js').write_text('window.SwaggerUIBundle = function() {};', encoding='utf-8')
            (src / 'swagger-ui-standalone-preset.js').write_text('window.SwaggerUIStandalonePreset = {};', encoding='utf-8')
            (src / 'favicon-32x32.png').write_bytes(b'\x89PNG\r\n\x1a\n')

            out = Path(directory) / 'artifact/ziper-api-docs.html'
            MOD.build_html(src, out, title='Ziper <API>')
            html_text = out.read_text(encoding='utf-8')

            self.assertIn('<title>Ziper &lt;API&gt;</title>', html_text)
            self.assertIn('spec: spec', html_text)
            self.assertIn('SwaggerUIBundle', html_text)
            self.assertIn('data:image/png;base64,', html_text)
            self.assertNotIn('url: "./openapi.json"', html_text)
            self.assertNotIn('</script><script>alert(1)', html_text)
            self.assertIn('<\\/script><script>alert(1)<\\/script>', html_text)
            self.assertNotIn('/* </style> */', html_text)

    def test_discord_target_and_multipart_delivery_against_local_http_fixture(self):
        self.assertEqual('1537457718326067330', MOD.DISCORD_CHANNEL_ID)
        url, headers = MOD.resolve_discord_target('1537457718326067330', 'bot-secret', '')
        self.assertEqual('https://discord.com/api/v10/channels/1537457718326067330/messages', url)
        self.assertEqual('Bot bot-secret', headers['Authorization'])

        with self.assertRaisesRegex(RuntimeError, 'Missing DISCORD_BOT_TOKEN'):
            MOD.resolve_discord_target('1537457718326067330', '', '')

        with self.assertRaisesRegex(ValueError, 'Invalid Discord webhook URL'):
            MOD.resolve_discord_target('1537457718326067330', '', 'https://evil.example/webhook')

        received = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get('Content-Length', '0'))
                received['path'] = self.path
                received['content_type'] = self.headers.get('Content-Type', '')
                received['body'] = self.rfile.read(length)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"id":"1"}')

            def log_message(self, fmt, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                html_path = Path(directory) / 'ziper-api-docs.html'
                html_path.write_text('<html><body>docs</body></html>', encoding='utf-8')
                diff = {
                    'baseline': False,
                    'changed': True,
                    'total': 11,
                    'added': ['POST /api/v2/items'],
                    'removed': ['POST /api/v1/items'],
                }
                local_url = f'http://127.0.0.1:{server.server_address[1]}/api/webhooks/1/tok?wait=true'
                with mock.patch.object(MOD, 'resolve_discord_target', return_value=(local_url, {'User-Agent': 'Test'})):
                    status = MOD.send_discord(
                        channel_id='1537457718326067330',
                        html_path=html_path,
                        diff=diff,
                        sha='1234567890abcdef1234567890abcdef12345678',
                        repository='JECT-Study/Ziper-5th-Server',
                        webhook_url='https://discord.com/api/webhooks/1/tok',
                    )
                self.assertEqual(200, status)
                self.assertIn('multipart/form-data; boundary=', received['content_type'])
                body_text = received['body'].decode('utf-8')
                self.assertIn('스웨거 대신 스웩이 넘치는 제가 HTML로 API 문서를 말아왔어요', body_text)
                self.assertIn('ziper-api-docs-1234567.html', body_text)
                self.assertIn('POST /api/v2/items', body_text)
                self.assertIn('POST /api/v1/items', body_text)
                self.assertIn('<html><body>docs</body></html>', body_text)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    unittest.main()
