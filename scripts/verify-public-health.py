#!/usr/bin/env python3
"""Verify the public HTTPS health contract without printing response contents."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import errno
from http.client import IncompleteRead
import json
from pathlib import Path
import re
import sys
import signal
import socket
import ssl
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, Request

RETRYABLE_HTTP = frozenset((502, 503, 504, 520, 521, 522, 523, 524, 530))
RETRYABLE_ERRNO = frozenset((errno.ECONNREFUSED, errno.ECONNRESET, errno.ECONNABORTED,
                             errno.EHOSTUNREACH, errno.ENETUNREACH, errno.ETIMEDOUT))


class HealthCheckError(ValueError):
    def __init__(self, reason, *, status=None, retryable=False):
        super().__init__(reason)
        self.status = status
        self.retryable = retryable


@contextmanager
def request_deadline(seconds):
    """Bound DNS, TLS, headers and body together on the POSIX deployment runner."""
    if not hasattr(signal, 'setitimer'):
        raise HealthCheckError('Health verification requires a POSIX runner')

    def expired(signum, frame):
        raise TimeoutError(errno.ETIMEDOUT, 'Health request deadline exceeded')

    previous_handler = signal.signal(signal.SIGALRM, expired)
    started = time.monotonic()
    previous_timer = signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        if previous_timer[0] > 0:
            signal.setitimer(signal.ITIMER_REAL,
                             max(0.000001, previous_timer[0] - (time.monotonic() - started)),
                             previous_timer[1])


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        response.close()
        raise HealthCheckError('Health endpoints must respond directly without a redirect', status=code)


def public_origin(value):
    parsed = urlsplit(value)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment):
        raise ValueError('Supply an HTTPS origin without credentials, a path, query, or fragment')
    try:
        parsed.port
    except ValueError:
        raise ValueError('Supply a valid HTTPS port') from None
    return value.rstrip('/')


def check_once(origin, path, timeout):
    try:
        with request_deadline(timeout):
            return request_once(origin, path, timeout)
    except URLError as error:
        reason = error.reason
        retryable = (isinstance(reason, (TimeoutError, socket.timeout, ConnectionError))
                     or isinstance(reason, socket.gaierror) and reason.errno == socket.EAI_AGAIN
                     or isinstance(reason, OSError) and reason.errno in RETRYABLE_ERRNO)
        if isinstance(reason, ssl.SSLError):
            retryable = False
        raise HealthCheckError('Health connection failed', retryable=retryable) from None
    except (TimeoutError, socket.timeout, ConnectionError, IncompleteRead):
        raise HealthCheckError('Health connection timed out or was interrupted', retryable=True) from None
    except OSError as error:
        raise HealthCheckError('Health transport failed', retryable=error.errno in RETRYABLE_ERRNO) from None


def request_once(origin, path, timeout):
    request = Request(origin + path, headers={
        'User-Agent': 'ziper-deployment-verification', 'Cache-Control': 'no-cache',
        'Accept': 'application/json',
    })
    try:
        response = build_opener(NoRedirect()).open(request, timeout=timeout)
    except HTTPError as error:
        error.close()
        raise HealthCheckError('Health endpoint returned HTTP ' + str(error.code),
                               status=error.code, retryable=error.code in RETRYABLE_HTTP) from None
    with response:
        if response.status != 200:
            raise HealthCheckError('Health endpoint did not return 200', status=response.status)
        directives = response.headers.get('Cache-Control', '').lower().split(',')
        if 'no-store' not in [item.strip() for item in directives]:
            raise HealthCheckError('Health endpoint must return Cache-Control: no-store', status=200)
        if response.headers.get('Set-Cookie'):
            raise HealthCheckError('Health endpoint must not create a session cookie', status=200)
        if response.headers.get('CF-Cache-Status', '').upper() in ('HIT', 'STALE', 'UPDATING'):
            raise HealthCheckError('Health endpoint was served from an edge cache', status=200)
        if response.headers.get_content_type() != 'application/json':
            raise HealthCheckError('Health endpoint must return JSON', status=200)
        try:
            body = response.read(4097)
        except (TimeoutError, socket.timeout, ConnectionError, IncompleteRead):
            raise HealthCheckError('Health response timed out or was interrupted',
                                   status=200, retryable=True) from None
        if len(body) > 4096:
            raise HealthCheckError('Health response exceeds the expected size', status=200)
        try:
            healthy = json.loads(body) == {'status': 'UP'}
        except (ValueError, UnicodeError):
            healthy = False
        if not healthy:
            raise HealthCheckError('Health endpoint must return only the UP status', status=200)
        release = response.headers.get('X-Ziper-Release', '')
        upstream = response.headers.get('X-Ziper-Origin', '')
    return {'path': path, 'status': 200, 'noStore': True, 'statusOnly': True,
            'release': release if re.fullmatch(r'[a-f0-9]{40}', release) else None,
            'upstreamOrigin': upstream if upstream in ('home', 'cloudrun') else None}


def verify(origin, *, checks=3, interval=10, timeout=10, startup_timeout=0, retry_interval=5,
           expected_release=None, expected_origin=None,
           check=check_once, now=time.monotonic, sleep=time.sleep, progress=None):
    started = now()
    deadline = started + startup_timeout if startup_timeout else None
    report = {'checkedAt': datetime.now(timezone.utc).isoformat(), 'origin': origin,
              'ok': False, 'samples': [], 'events': [], 'attempts': 0,
              'consecutiveSuccesses': 0, 'termination': 'running', 'elapsedSeconds': 0}
    if expected_release is not None:
        report.update(expectedRelease=expected_release, expectedOrigin=expected_origin)

    def publish():
        report['elapsedSeconds'] = round(now() - started, 3)
        if progress:
            progress(report)

    def finish(termination, error=None):
        report.update(termination=termination, elapsedSeconds=round(now() - started, 3))
        if error:
            report['error'] = error
        publish()
        return report

    while deadline is None or now() < deadline:
        report['attempts'] += 1
        sample = []
        failure = None
        for path in ('/livez', '/readyz'):
            remaining = deadline - now() if deadline is not None else timeout
            if remaining <= 0:
                return finish('deadline', 'Public health startup deadline exceeded')
            request_timeout = min(timeout, remaining)
            event = {'attempt': report['attempts'], 'path': path,
                     'elapsedSeconds': round(now() - started, 3),
                     'timeoutSeconds': round(request_timeout, 3), 'status': None,
                     'retryable': False, 'result': 'requesting'}
            report['events'].append(event)
            publish()
            try:
                result = check(origin, path, request_timeout)
                if expected_release is not None and result.get('release') != expected_release:
                    raise HealthCheckError('Health release does not match the deployment', status=200)
                if expected_origin is not None and result.get('upstreamOrigin') != expected_origin:
                    raise HealthCheckError('Health origin does not match the deployment', status=200)
                sample.append(result)
                event.update(status=200, retryable=False, result='healthy')
            except HealthCheckError as error:
                failure = error
                event.update(status=error.status, retryable=error.retryable,
                             result='failed', reason=str(error))
            event['elapsedSeconds'] = round(now() - started, 3)
            publish()
            if failure:
                break
        if failure:
            report['consecutiveSuccesses'] = 0
            report['samples'] = []
            if not failure.retryable or deadline is None:
                return finish('non_retryable' if not failure.retryable else 'fail_fast', str(failure))
        else:
            report['consecutiveSuccesses'] += 1
            report['samples'].append(sample)
        if deadline is not None and now() >= deadline:
            return finish('deadline', 'Public health startup deadline exceeded')
        if report['consecutiveSuccesses'] == checks:
            report['ok'] = True
            return finish('healthy')
        delay = retry_interval if failure else interval
        if deadline is not None:
            delay = min(delay, max(0, deadline - now()))
        sleep(delay)
    return finish('deadline', 'Public health startup deadline exceeded')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--checks', type=int, choices=range(1, 11), default=3,
                        help='Required consecutive successful pairs of /livez and /readyz')
    parser.add_argument('--interval', type=int, choices=range(1, 61), default=10)
    parser.add_argument('--timeout', type=int, choices=range(1, 31), default=10)
    parser.add_argument('--startup-timeout', type=int, choices=range(0, 601), default=0,
                        help='Total seconds for startup retries; 0 preserves fail-fast behavior')
    parser.add_argument('--retry-interval', type=int, choices=range(1, 61), default=5,
                        help='Seconds between retryable failures during startup')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--expected-release', help='Expected full source SHA; requires --expected-origin')
    parser.add_argument('--expected-origin', help='Expected home or cloudrun; requires --expected-release')
    args = parser.parse_args()
    if (args.expected_release is not None or args.expected_origin is not None) and (
            not re.fullmatch(r'[a-f0-9]{40}', args.expected_release or '')
            or args.expected_origin not in ('home', 'cloudrun')):
        parser.error('Supply both a full lowercase source SHA and a home or cloudrun expected origin')
    try:
        args.url = public_origin(args.url)
    except ValueError:
        parser.error('Supply an HTTPS origin without credentials, a path, query, or fragment')
    def save_progress(report):
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            pending = args.output.with_name(args.output.name + '.next')
            pending.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            pending.replace(args.output)

    report = verify(args.url, checks=args.checks, interval=args.interval, timeout=args.timeout,
                    startup_timeout=args.startup_timeout, retry_interval=args.retry_interval,
                    expected_release=args.expected_release, expected_origin=args.expected_origin,
                    progress=save_progress)
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    print(text, end='')
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
