#!/usr/bin/env python3
"""Exercise the deployment verifier against a real local HTTP fixture."""
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import subprocess
import sys
import unittest
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location('verifier', Path(__file__).with_name('verify-public-health.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class PublicHealthTests(unittest.TestCase):
    def test_cli_does_not_echo_credentials_from_an_invalid_origin(self):
        marker = 'private-marker-that-must-not-be-printed'
        command = [sys.executable, str(Path(__file__).with_name('verify-public-health.py')),
                   '--url', 'https://user:' + marker + '@api.example.com']
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertNotIn(marker, result.stdout + result.stderr)

    def test_https_origin_rejects_credentials_and_non_origin_values(self):
        self.assertEqual('https://api.example.com', verifier.public_origin('https://api.example.com/'))
        for value in ('http://api.example.com', 'https://user@example.com',
                      'https://api.example.com/readyz', 'https://api.example.com?key=value',
                      'https://api.example.com/#fragment', 'https://api.example.com:invalid'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                verifier.public_origin(value)

    def test_real_http_contract_accepts_health_and_rejects_misleading_success(self):
        class Handler(BaseHTTPRequestHandler):
            code = 200
            headers_to_send = {'Cache-Control': 'no-store', 'Content-Type': 'application/json'}
            body = {'status': 'UP'}

            def do_GET(self):
                self.send_response(self.code)
                for key, value in self.headers_to_send.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(json.dumps(self.body).encode())

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            origin = 'http://127.0.0.1:' + str(server.server_port)
            try:
                for path in ('/livez', '/readyz'):
                    self.assertEqual(200, verifier.check_once(origin, path, 2)['status'])
                for change in (
                    {'body': {'status': 'DOWN'}},
                    {'body': {'status': 'UP', 'components': {'db': {'status': 'UP'}}}},
                    {'headers_to_send': {'Content-Type': 'application/json'}},
                    {'headers_to_send': {**Handler.headers_to_send, 'Set-Cookie': 'session=fixture'}},
                    {'headers_to_send': {**Handler.headers_to_send, 'CF-Cache-Status': 'HIT'}},
                    {'headers_to_send': {**Handler.headers_to_send, 'Content-Type': 'text/html'}},
                    {'code': 302, 'headers_to_send': {'Location': origin + '/login'}},
                    {'code': 503},
                ):
                    previous = {key: getattr(Handler, key) for key in change}
                    try:
                        for key, value in change.items():
                            setattr(Handler, key, value)
                        with self.subTest(change=change), self.assertRaises((ValueError, HTTPError)):
                            verifier.check_once(origin, '/readyz', 2)
                    finally:
                        for key, value in previous.items():
                            setattr(Handler, key, value)
            finally:
                server.shutdown()
                thread.join()


if __name__ == '__main__':
    unittest.main()
