#!/usr/bin/env python3
"""Exercise the deployment verifier against a real local HTTP fixture."""
import importlib.util
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import subprocess
import sys
import shutil
import tempfile
import unittest
from urllib.error import HTTPError
from urllib.error import URLError
import ssl
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('verifier', Path(__file__).with_name('verify-public-health.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class Clock:
    def __init__(self):
        self.elapsed = 0

    def now(self):
        return self.elapsed

    def sleep(self, seconds):
        self.elapsed += seconds


@contextmanager
def sequence_server(statuses, *, release=None, upstream=None):
    requests = []
    pending = list(statuses)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append({'path': self.path, 'accept': self.headers.get('Accept'),
                             'userAgent': self.headers.get('User-Agent')})
            self.send_response(pending.pop(0) if pending else 200)
            self.send_header('Content-Type', 'application/json' if self.headers.get('Accept') == 'application/json'
                             else 'application/vnd.spring-boot.actuator.v3+json')
            self.send_header('Cache-Control', 'no-store')
            if release is not None:
                self.send_header('X-Ziper-Release', release)
            if upstream is not None:
                self.send_header('X-Ziper-Origin', upstream)
            self.end_headers()
            self.wfile.write(b'{"status":"UP"}')

        def log_message(self, *args):
            pass

    with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield 'http://127.0.0.1:' + str(server.server_port), requests
        finally:
            server.shutdown()
            thread.join()


class PublicHealthTests(unittest.TestCase):
    def test_expected_release_and_origin_are_checked_on_both_paths(self):
        clock = Clock()
        with sequence_server([], release='a' * 40, upstream='home') as (origin, requests):
            report = verifier.verify(origin, checks=1, expected_release='a' * 40,
                                     expected_origin='home', now=clock.now, sleep=clock.sleep)
        self.assertTrue(report['ok'])
        self.assertEqual([item['path'] for item in requests], ['/livez', '/readyz'])
        self.assertEqual([item['release'] for item in report['samples'][0]], ['a' * 40] * 2)
        self.assertEqual(report['expectedRelease'], 'a' * 40)

    def test_old_missing_and_invalid_identity_fail_without_retry_or_disclosure(self):
        marker = 'private-untrusted-header'
        for release, upstream in [('b' * 40, 'home'), ('a' * 40, 'cloudrun'),
                                  (None, None), (marker, marker)]:
            with self.subTest(release=release, upstream=upstream):
                clock = Clock()
                with sequence_server([], release=release, upstream=upstream) as (origin, requests):
                    report = verifier.verify(origin, startup_timeout=180, expected_release='a' * 40,
                                             expected_origin='home', now=clock.now, sleep=clock.sleep)
                self.assertFalse(report['ok'])
                self.assertEqual(report['termination'], 'non_retryable')
                self.assertEqual(len(requests), 1)
                self.assertEqual(clock.elapsed, 0)
                self.assertNotIn(marker, json.dumps(report))

    def test_readyz_identity_mismatch_cannot_pass_after_livez_matches(self):
        clock = Clock()
        def check(origin, path, timeout):
            return {'path': path, 'status': 200, 'upstreamOrigin': 'home',
                    'release': ('a' if path == '/livez' else 'b') * 40}
        report = verifier.verify('https://fixture.test', check=check, startup_timeout=180,
                                 expected_release='a' * 40, expected_origin='home',
                                 now=clock.now, sleep=clock.sleep)
        self.assertFalse(report['ok'])
        self.assertEqual(report['events'][-1]['path'], '/readyz')
        self.assertEqual(report['attempts'], 1)

    def test_cli_rejects_partial_or_invalid_identity_without_echoing_values(self):
        marker = 'private-untrusted-header'
        for flags in [ ['--expected-release', 'a' * 40], ['--expected-origin', 'home'],
                       ['--expected-release', marker, '--expected-origin', 'home'],
                       ['--expected-release', 'a' * 40, '--expected-origin', marker] ]:
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('verify-public-health.py')),
                                     '--url', 'https://fixture.test', *flags], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn(marker, result.stdout + result.stderr)

    @unittest.skipUnless(shutil.which('timeout'), 'GNU timeout is provided by the Linux Actions runner')
    def test_outer_deadline_kills_blocked_cli_and_preserves_last_request(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'health.json'
            code = ('import runpy,sys,time; '
                    'm=runpy.run_path(sys.argv[1]); '
                    'm["check_once"].__globals__["request_once"]=lambda *a: time.sleep(30); '
                    'sys.argv=[sys.argv[1],"--url","https://fixture.test","--startup-timeout","5","--output",sys.argv[2]]; '
                    'm["main"]()')
            result = subprocess.run(['timeout', '--signal=KILL', '1s', sys.executable, '-c', code,
                                     str(Path(__file__).with_name('verify-public-health.py')), str(output)],
                                    capture_output=True, timeout=3)
            self.assertIn(result.returncode, (124, 137, -9))
            report = json.loads(output.read_text())
            self.assertFalse(report['ok'])
            self.assertEqual(report['events'][-1]['path'], '/livez')
            self.assertEqual(report['events'][-1]['result'], 'requesting')

    def test_actual_request_negotiates_actuator_json_and_keeps_user_agent(self):
        with sequence_server([]) as (origin, requests):
            self.assertEqual(200, verifier.check_once(origin, '/readyz', 2)['status'])
            self.assertEqual(requests, [{'path': '/readyz', 'accept': 'application/json',
                                         'userAgent': 'ziper-deployment-verification'}])

    def test_real_530_recovers_after_three_successful_pairs(self):
        clock = Clock()
        with sequence_server([530]) as (origin, requests):
            report = verifier.verify(origin, startup_timeout=180, now=clock.now, sleep=clock.sleep)
        self.assertTrue(report['ok'])
        self.assertEqual(report['attempts'], 4)
        self.assertEqual(report['consecutiveSuccesses'], 3)
        self.assertEqual(len(requests), 7)
        self.assertEqual(clock.elapsed, 25)
        self.assertEqual(report['events'][0]['path'], '/livez')
        self.assertEqual(report['events'][0]['status'], 530)
        self.assertEqual(report['events'][0]['elapsedSeconds'], 0)

    def test_retryable_readyz_failure_resets_completed_pairs(self):
        clock = Clock()
        with sequence_server([200, 200, 200, 530]) as (origin, requests):
            report = verifier.verify(origin, startup_timeout=180, now=clock.now, sleep=clock.sleep)
        self.assertTrue(report['ok'])
        self.assertEqual(report['attempts'], 5)
        self.assertEqual(len(report['samples']), 3)
        self.assertEqual(len(requests), 10)
        self.assertEqual(report['events'][3]['path'], '/readyz')
        self.assertEqual(report['events'][3]['status'], 530)

    def test_selected_transient_http_statuses_retry_then_recover(self):
        statuses = [502, 503, 504, 520, 521, 522, 523, 524, 530]
        clock = Clock()
        with sequence_server(statuses) as (origin, requests):
            report = verifier.verify(origin, startup_timeout=180, now=clock.now, sleep=clock.sleep)
        self.assertTrue(report['ok'])
        self.assertEqual(report['attempts'], len(statuses) + 3)
        self.assertEqual([event['status'] for event in report['events'] if event['result'] == 'failed'], statuses)
        self.assertEqual(len(requests), len(statuses) + 6)

    def test_persistent_530_ends_at_deadline_without_sleeping_in_real_time(self):
        clock = Clock()
        with sequence_server([530] * 10) as (origin, requests):
            report = verifier.verify(origin, startup_timeout=12, now=clock.now, sleep=clock.sleep)
        self.assertFalse(report['ok'])
        self.assertEqual(report['termination'], 'deadline')
        self.assertEqual(report['attempts'], 3)
        self.assertEqual(len(requests), 3)
        self.assertEqual(report['elapsedSeconds'], 12)
        self.assertEqual(report['events'][-1]['timeoutSeconds'], 2)

    def test_authentication_and_other_non_retryable_http_fail_immediately(self):
        for status in (401, 403, 404, 429, 500, 501, 525, 526):
            clock = Clock()
            with self.subTest(status=status), sequence_server([status]) as (origin, requests):
                report = verifier.verify(origin, startup_timeout=180, now=clock.now, sleep=clock.sleep)
                self.assertFalse(report['ok'])
                self.assertEqual(report['termination'], 'non_retryable')
                self.assertEqual(len(requests), 1)
                self.assertEqual(clock.elapsed, 0)
                self.assertEqual(report['events'][0]['status'], status)

    def test_contract_violation_is_never_retried(self):
        clock = Clock()

        def invalid(origin, path, timeout):
            raise verifier.HealthCheckError('Health endpoint must return JSON', status=200)

        report = verifier.verify('https://fixture.test', check=invalid, startup_timeout=180,
                                 now=clock.now, sleep=clock.sleep)
        self.assertFalse(report['ok'])
        self.assertEqual(report['attempts'], 1)
        self.assertEqual(report['termination'], 'non_retryable')
        self.assertEqual(report['events'][0]['status'], 200)

    def test_default_mode_keeps_first_error_fail_fast(self):
        clock = Clock()
        with sequence_server([530]) as (origin, requests):
            report = verifier.verify(origin, now=clock.now, sleep=clock.sleep)
        self.assertFalse(report['ok'])
        self.assertEqual(report['termination'], 'fail_fast')
        self.assertEqual(len(requests), 1)
        self.assertEqual(clock.elapsed, 0)

    def test_request_uses_remaining_budget_and_late_success_cannot_pass(self):
        clock = Clock()
        timeouts = []

        def slow(origin, path, timeout):
            timeouts.append(timeout)
            clock.sleep(timeout)
            raise verifier.HealthCheckError('Health connection failed', retryable=True)

        report = verifier.verify('https://fixture.test', check=slow, startup_timeout=12,
                                 now=clock.now, sleep=clock.sleep)
        self.assertEqual(timeouts, [10])
        self.assertFalse(report['ok'])
        self.assertEqual(report['elapsedSeconds'], 12)

        def late(origin, path, timeout):
            clock.sleep(timeout)
            return {'path': path, 'status': 200}

        report = verifier.verify('https://fixture.test', check=late, checks=1, startup_timeout=12,
                                 now=clock.now, sleep=clock.sleep)
        self.assertFalse(report['ok'])
        self.assertEqual(report['termination'], 'deadline')

    def test_connection_refused_is_retryable_but_certificate_error_is_not(self):
        for cause, retryable in ((ConnectionRefusedError(), True), (ssl.SSLCertVerificationError(), False)):
            with self.subTest(cause=type(cause).__name__), patch.object(verifier, 'build_opener') as opener:
                opener.return_value.open.side_effect = URLError(cause)
                with self.assertRaises(verifier.HealthCheckError) as caught:
                    verifier.check_once('https://fixture.test', '/readyz', 2)
                self.assertEqual(caught.exception.retryable, retryable)

    def test_body_read_is_bounded_by_request_deadline(self):
        release = threading.Event()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                release.wait(1)

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with self.assertRaises(verifier.HealthCheckError) as caught:
                    verifier.check_once('http://127.0.0.1:' + str(server.server_port), '/readyz', 0.05)
                self.assertTrue(caught.exception.retryable)
                self.assertEqual(caught.exception.status, 200)
            finally:
                release.set()
                server.shutdown()
                thread.join()

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
                    if key == 'Content-Type' and value == 'application/json' and self.headers.get('Accept') != 'application/json':
                        value = 'application/vnd.spring-boot.actuator.v3+json'
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(self.body if isinstance(self.body, bytes) else json.dumps(self.body).encode())

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
                    {'headers_to_send': {**Handler.headers_to_send, 'CF-Cache-Status': 'STALE'}},
                    {'headers_to_send': {**Handler.headers_to_send, 'CF-Cache-Status': 'UPDATING'}},
                    {'headers_to_send': {**Handler.headers_to_send, 'Content-Type': 'text/html'}},
                    {'headers_to_send': {**Handler.headers_to_send, 'Content-Type': 'application/vnd.spring-boot.actuator.v3+json'}},
                    {'body': 'x' * 4097},
                    {'body': b'not-json-private-marker'},
                    {'code': 302, 'headers_to_send': {'Location': origin + '/login'}},
                    {'code': 503},
                ):
                    previous = {key: getattr(Handler, key) for key in change}
                    try:
                        for key, value in change.items():
                            setattr(Handler, key, value)
                        with self.subTest(change=change), self.assertRaises((ValueError, HTTPError)):
                            verifier.check_once(origin, '/readyz', 2)
                        clock = Clock()
                        report = verifier.verify(origin, startup_timeout=180, now=clock.now, sleep=clock.sleep)
                        self.assertFalse(report['ok'])
                        self.assertNotIn('private-marker', json.dumps(report))
                        if change.get('code') != 503:
                            self.assertEqual(report['attempts'], 1)
                            self.assertEqual(clock.elapsed, 0)
                    finally:
                        for key, value in previous.items():
                            setattr(Handler, key, value)
            finally:
                server.shutdown()
                thread.join()


if __name__ == '__main__':
    unittest.main()
