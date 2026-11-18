#!/usr/bin/env python3
"""Build self-contained static OpenAPI HTML, diff endpoints, and notify Discord."""
import argparse
import base64
import html
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
import uuid

DISCORD_CHANNEL_ID = '1537457718326067330'
HTTP_METHODS = frozenset({'get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'})
DISCORD_MESSAGE_LIMIT = 2000


def extract_endpoints(spec):
    """Return sorted list of 'METHOD /path' strings from an OpenAPI spec dict."""
    paths = spec.get('paths')
    if not isinstance(paths, dict):
        return []
    endpoints = set()
    for path, operations in paths.items():
        if not isinstance(operations, dict):
            continue
        for method in operations:
            if isinstance(method, str) and method.lower() in HTTP_METHODS:
                endpoints.add(f'{method.upper()} {path}')
    return sorted(endpoints)


def diff_endpoints(head_spec, before_spec=None, force=False):
    """Compare endpoint sets between before and head specs."""
    head_eps = set(extract_endpoints(head_spec))
    baseline = before_spec is None
    before_eps = set() if baseline else set(extract_endpoints(before_spec))
    added = sorted(head_eps - before_eps)
    removed = sorted(before_eps - head_eps)
    changed = bool(added or removed)
    return {
        'baseline': baseline,
        'changed': changed,
        'should_publish': bool(changed or force),
        'total': len(head_eps),
        'added': added,
        'removed': removed,
    }


def _resolve_json_pointer(root, ref):
    if not isinstance(ref, str) or not ref.startswith('#/'):
        return None
    node = root
    for raw_part in ref[2:].split('/'):
        part = raw_part.replace('~1', '/').replace('~0', '~')
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def _dereference_node(node, root, seen):
    if isinstance(node, list):
        return [_dereference_node(item, root, seen) for item in node]
    if not isinstance(node, dict):
        return node
    ref = node.get('$ref')
    if isinstance(ref, str) and ref.startswith('#/') and ref not in seen:
        target = _resolve_json_pointer(root, ref)
        if isinstance(target, dict):
            merged = dict(target)
            for key, value in node.items():
                if key != '$ref':
                    merged[key] = value
            return _dereference_node(merged, root, seen | {ref})
    return {key: _dereference_node(value, root, seen) for key, value in node.items()}


def inline_local_refs(spec):
    """Inline local #/... JSON references so file:// Swagger UI renders without Resolver errors."""
    resolved = _dereference_node(spec, spec, frozenset())
    if isinstance(resolved, dict):
        resolved.pop('servers', None)
    return resolved


def _escape_style(text):
    return re.sub(r'</(style)', r'<\\/\1', text, flags=re.IGNORECASE)


def _escape_script(text):
    return re.sub(r'</(script)', r'<\\/\1', text, flags=re.IGNORECASE)


def _embed_json(data):
    dumped = json.dumps(data, ensure_ascii=False)
    return dumped.replace('</', '<\\/').replace('\u2028', '\\u2028').replace('\u2029', '\\u2029')


def build_html(input_dir, output_path, title='Ziper API Documentation'):
    """Bundle OpenAPI JSON and Swagger UI assets into one self-contained HTML file."""
    src = Path(input_dir)
    raw_spec = json.loads((src / 'openapi.json').read_text(encoding='utf-8'))
    spec = inline_local_refs(raw_spec)
    swagger_css = _escape_style((src / 'swagger-ui.css').read_text(encoding='utf-8'))
    index_css = _escape_style((src / 'index.css').read_text(encoding='utf-8'))
    bundle_js = _escape_script((src / 'swagger-ui-bundle.js').read_text(encoding='utf-8'))
    preset_js = _escape_script((src / 'swagger-ui-standalone-preset.js').read_text(encoding='utf-8'))

    favicon_tags = []
    for size in ('32x32', '16x16'):
        icon_file = src / f'favicon-{size}.png'
        if icon_file.is_file():
            encoded = base64.b64encode(icon_file.read_bytes()).decode('ascii')
            favicon_tags.append(
                f'    <link rel="icon" type="image/png" href="data:image/png;base64,{encoded}" sizes="{size}" />'
            )

    spec_json = _embed_json(spec)
    safe_title = html.escape(title, quote=True)
    document = '\n'.join([
        '<!DOCTYPE html>',
        '<html lang="ko">',
        '  <head>',
        '    <meta charset="UTF-8" />',
        '    <meta name="viewport" content="width=device-width, initial-scale=1.0" />',
        f'    <title>{safe_title}</title>',
        *favicon_tags,
        f'    <style>\n{swagger_css}\n{index_css}\n    </style>',
        '  </head>',
        '  <body>',
        '    <div id="swagger-ui"></div>',
        f'    <script>\n{bundle_js}\n    </script>',
        f'    <script>\n{preset_js}\n    </script>',
        '    <script>',
        '      window.onload = function() {',
        f'        const spec = {spec_json};',
        '        window.ui = SwaggerUIBundle({',
        '          spec: spec,',
        '          dom_id: "#swagger-ui",',
        '          deepLinking: true,',
        '          supportedSubmitMethods: [],',
        '          defaultModelRendering: "model",',
        '          defaultModelsExpandDepth: 1,',
        '          defaultModelExpandDepth: 5,',
        '          presets: [',
        '            SwaggerUIBundle.presets.apis,',
        '            SwaggerUIStandalonePreset',
        '          ],',
        '          plugins: [',
        '            SwaggerUIBundle.plugins.DownloadUrl',
        '          ],',
        '          layout: "StandaloneLayout",',
        '          persistAuthorization: false,',
        '          validatorUrl: ""',
        '        });',
        '      };',
        '    </script>',
        '  </body>',
        '</html>',
        '',
    ])

    target = Path(output_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(document, encoding='utf-8')
    return target


def _format_endpoint_section(label, items, max_items=15):
    if not items:
        return []
    lines = [f'**{label} ({len(items)})**']
    for item in items[:max_items]:
        lines.append(f'- `{item}`')
    if len(items) > max_items:
        lines.append(f'- ... 외 {len(items) - max_items}개')
    return lines


def format_discord_content(diff, sha='', repository=''):
    """Format a Discord message under Discord's 2000-character limit."""
    short_sha = sha[:7] if sha else 'local'
    lead = '스웨거 대신 스웩이 넘치는 제가 HTML로 API 문서를 말아왔어요'
    if repository and sha:
        header = f'**{lead}** ([`{short_sha}`](https://github.com/{repository}/commit/{sha}) · `main`)'
    else:
        header = f'**{lead}** (`{short_sha}`)'

    lines = [header]
    if diff.get('baseline'):
        lines.append(f'초기 기준 OpenAPI 정적 HTML이 생성되었습니다. (전체 `{diff["total"]}`개 엔드포인트)')
    elif not diff.get('changed'):
        lines.append(f'엔드포인트 증감 없이 수동 발행되었습니다. (전체 `{diff["total"]}`개 엔드포인트)')
    else:
        lines.append(
            f'전체 `{diff["total"]}`개 엔드포인트 (+{len(diff["added"])} / -{len(diff["removed"])})'
        )
    lines.extend(_format_endpoint_section('추가된 엔드포인트', diff.get('added', [])))
    lines.extend(_format_endpoint_section('제거된 엔드포인트', diff.get('removed', [])))
    lines.append('첨부된 HTML 파일을 내려받아 브라우저에서 바로 열 수 있습니다.')

    content = '\n'.join(lines)
    if len(content) <= DISCORD_MESSAGE_LIMIT:
        return content
    compact = '\n'.join([
        header,
        f'전체 `{diff["total"]}`개 엔드포인트 (+{len(diff["added"])} / -{len(diff["removed"])})',
        '자세한 목록은 첨부된 HTML 파일 또는 GitHub Actions 요약을 확인해주세요.',
    ])
    return compact[:DISCORD_MESSAGE_LIMIT]


def build_multipart_payload(payload_json, filename, file_bytes, boundary=None):
    """Construct a multipart/form-data body for Discord REST API."""
    boundary = boundary or f'----ZiperOpenApi{uuid.uuid4().hex}'
    parts = [
        f'--{boundary}\r\n'.encode('utf-8'),
        b'Content-Disposition: form-data; name="payload_json"\r\n',
        b'Content-Type: application/json; charset=utf-8\r\n\r\n',
        json.dumps(payload_json, ensure_ascii=False).encode('utf-8'),
        b'\r\n',
        f'--{boundary}\r\n'.encode('utf-8'),
        f'Content-Disposition: form-data; name="files[0]"; filename="{filename}"\r\n'.encode('utf-8'),
        b'Content-Type: text/html; charset=utf-8\r\n\r\n',
        file_bytes,
        b'\r\n',
        f'--{boundary}--\r\n'.encode('utf-8'),
    ]
    return b''.join(parts), f'multipart/form-data; boundary={boundary}'


def resolve_discord_target(channel_id, bot_token, webhook_url):
    """Resolve endpoint URL and headers from provided Discord credentials."""
    token = (bot_token or '').strip()
    webhook = (webhook_url or '').strip()
    if token:
        if not re.fullmatch(r'\d{15,25}', str(channel_id)):
            raise ValueError('Invalid Discord channel ID')
        return (
            f'https://discord.com/api/v10/channels/{channel_id}/messages',
            {
                'Authorization': f'Bot {token}',
                'User-Agent': 'DiscordBot (https://github.com/JECT-Study/Ziper-5th-Server, 1.0)',
            },
        )
    if webhook:
        if not re.match(r'^https://(?:discord|discordapp)\.com/api/webhooks/\d+/[^/\s?#]+$', webhook):
            raise ValueError('Invalid Discord webhook URL format')
        return (
            f'{webhook}?wait=true',
            {
                'User-Agent': 'Ziper-OpenAPI-Notifier/1.0',
            },
        )
    raise RuntimeError(
        f'Missing DISCORD_BOT_TOKEN or DISCORD_OPENAPI_WEBHOOK_URL secret for channel {channel_id}'
    )


def send_discord(channel_id, html_path, diff, sha='', repository='', bot_token='', webhook_url=''):
    """Send static HTML file attachment and endpoint summary to Discord."""
    url, headers = resolve_discord_target(channel_id, bot_token, webhook_url)
    html_file = Path(html_path)
    file_bytes = html_file.read_bytes()
    short_sha = sha[:7] if sha else ''
    attachment_name = f'ziper-api-docs-{short_sha}.html' if short_sha else html_file.name
    content = format_discord_content(diff, sha=sha, repository=repository)
    body, content_type = build_multipart_payload({'content': content}, attachment_name, file_bytes)
    request = urllib.request.Request(
        url,
        data=body,
        headers={**headers, 'Content-Type': content_type},
        method='POST',
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = getattr(response, 'status', response.getcode())
            if status < 200 or status >= 300:
                raise RuntimeError(f'Discord API returned unexpected HTTP {status}')
            return status
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'Discord API request failed with HTTP {error.code}') from None
    except urllib.error.URLError as error:
        raise RuntimeError(f'Discord API connection failed: {error.reason}') from None


def _cmd_build_html(args):
    build_html(args.input_dir, args.output, title=args.title)
    return 0


def _cmd_diff(args):
    head_spec = json.loads(Path(args.head).read_text(encoding='utf-8'))
    before_spec = json.loads(Path(args.before).read_text(encoding='utf-8')) if args.before else None
    result = diff_endpoints(head_spec, before_spec=before_spec, force=args.force)
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(rendered + '\n', encoding='utf-8')
    else:
        print(rendered)
    return 0


def _cmd_send_discord(args):
    diff = json.loads(Path(args.diff_json).read_text(encoding='utf-8'))
    status = send_discord(
        channel_id=args.channel_id,
        html_path=args.html,
        diff=diff,
        sha=args.sha,
        repository=args.repository,
        bot_token=os.environ.get('DISCORD_BOT_TOKEN', ''),
        webhook_url=os.environ.get('DISCORD_OPENAPI_WEBHOOK_URL', ''),
    )
    print(f'Discord delivery succeeded (HTTP {status}) for channel {args.channel_id}.')
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='command', required=True)

    p_build = subparsers.add_parser('build-html', help='Build a single self-contained Swagger UI HTML')
    p_build.add_argument('--input-dir', required=True)
    p_build.add_argument('--output', required=True)
    p_build.add_argument('--title', default='Ziper API Documentation')
    p_build.set_defaults(func=_cmd_build_html)

    p_diff = subparsers.add_parser('diff', help='Diff OpenAPI endpoints between before and head')
    p_diff.add_argument('--head', required=True)
    p_diff.add_argument('--before', default='')
    p_diff.add_argument('--force', action='store_true')
    p_diff.add_argument('--output', default='')
    p_diff.set_defaults(func=_cmd_diff)

    p_discord = subparsers.add_parser('send-discord', help='Send static HTML artifact to Discord')
    p_discord.add_argument('--channel-id', default=DISCORD_CHANNEL_ID)
    p_discord.add_argument('--html', required=True)
    p_discord.add_argument('--diff-json', required=True)
    p_discord.add_argument('--sha', default='')
    p_discord.add_argument('--repository', default='')
    p_discord.set_defaults(func=_cmd_send_discord)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (RuntimeError, ValueError, OSError) as exc:
        print(f'OpenAPI docs error: {exc}', file=sys.stderr)
        sys.exit(1)
