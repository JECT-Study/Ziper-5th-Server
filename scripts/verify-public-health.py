#!/usr/bin/env python3
"""Verify the public HTTPS health contract without printing response contents."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, Request


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise ValueError('Health endpoints must respond directly without a redirect')


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
    request = Request(origin + path, headers={
        'User-Agent': 'ziper-deployment-verification', 'Cache-Control': 'no-cache',
        'Accept': 'application/json',
    })
    try:
        response = build_opener(NoRedirect()).open(request, timeout=timeout)
    except HTTPError as error:
        error.close()
        raise ValueError('Health endpoint returned HTTP ' + str(error.code)) from None
    with response:
        if response.status != 200:
            raise ValueError('Health endpoint did not return 200')
        directives = response.headers.get('Cache-Control', '').lower().split(',')
        if 'no-store' not in [item.strip() for item in directives]:
            raise ValueError('Health endpoint must return Cache-Control: no-store')
        if response.headers.get('Set-Cookie'):
            raise ValueError('Health endpoint must not create a session cookie')
        if response.headers.get('CF-Cache-Status', '').upper() in ('HIT', 'STALE', 'UPDATING'):
            raise ValueError('Health endpoint was served from an edge cache')
        if response.headers.get_content_type() != 'application/json':
            raise ValueError('Health endpoint must return JSON')
        body = response.read(4097)
        if len(body) > 4096:
            raise ValueError('Health response exceeds the expected size')
        try:
            healthy = json.loads(body) == {'status': 'UP'}
        except (ValueError, UnicodeError):
            healthy = False
        if not healthy:
            raise ValueError('Health endpoint must return only the UP status')
    return {'path': path, 'status': 200, 'noStore': True, 'statusOnly': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--checks', type=int, choices=range(1, 11), default=3)
    parser.add_argument('--interval', type=int, choices=range(1, 61), default=10)
    parser.add_argument('--timeout', type=int, choices=range(1, 31), default=10)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        args.url = public_origin(args.url)
    except ValueError:
        parser.error('Supply an HTTPS origin without credentials, a path, query, or fragment')
    report = {'checkedAt': datetime.now(timezone.utc).isoformat(), 'origin': args.url,
              'ok': False, 'samples': []}
    try:
        for index in range(args.checks):
            report['samples'].append([check_once(args.url, path, args.timeout)
                                      for path in ('/livez', '/readyz')])
            if index + 1 < args.checks:
                time.sleep(args.interval)
        report['ok'] = True
    except ValueError as error:
        report['error'] = str(error)
    except (URLError, TimeoutError, OSError):
        report['error'] = 'Health request failed during DNS, TLS, connection, or response handling'
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    print(text, end='')
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
