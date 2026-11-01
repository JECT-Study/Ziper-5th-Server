#!/usr/bin/env python3
"""Validate maintained-document routing and narrow BusinessCatch exceptions as data."""
import argparse
import json
from pathlib import Path, PurePosixPath
import re
import sys
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

LINK = re.compile(r'\[[^\]]+\]\(([^\s)]+)\)')


def normalized(value):
    if not isinstance(value, str) or not value or '\\' in value or '\x00' in value:
        raise ValueError('Invalid repository path')
    path = PurePosixPath(value)
    if path.is_absolute() or any(p in ('', '.', '..') for p in value.split('/')):
        raise ValueError('Repository paths must be normalized and cannot traverse')
    return value


def local_file(repo, value):
    normalized(value)
    target = repo / value
    if any((repo / Path(*PurePosixPath(value).parts[:index])).is_symlink() for index in range(1, len(PurePosixPath(value).parts) + 1)):
        raise ValueError(f'Symlink forbidden: {value}')
    if not target.is_file() or not target.resolve().is_relative_to(repo.resolve()):
        raise ValueError(f'Missing repository file: {value}')
    return target


def doc_link(repo, href, relative=''):
    url = urlsplit(href)
    if url.scheme or url.netloc or url.query:
        raise ValueError(f'Documentation links must be local: {href}')
    raw = unquote(url.path)
    if not raw:
        raise ValueError('Documentation link needs a file path')
    # Map links may use one parent step; resolve lexically and reject escape.
    parts = list(PurePosixPath(relative).parts)
    for part in raw.split('/'):
        if part == '..':
            if not parts:
                raise ValueError('Documentation link escapes repository')
            parts.pop()
        elif part not in ('', '.'):
            parts.append(part)
    if raw.startswith('/') or '\\' in raw:
        raise ValueError('Documentation link must be repository relative')
    value = '/'.join(parts)
    local_file(repo, value)
    if not value.endswith('.md'):
        raise ValueError('Documentation link must name Markdown')
    return value


def load_policy(repo):
    config = json.loads(local_file(repo, 'config/harness/doc-policy.json').read_text())
    if set(config) != {'version', 'maintained_docs', 'routes'} or config['version'] != 1:
        raise ValueError('Unsupported document policy schema')
    docs, routes = config['maintained_docs'], config['routes']
    if not isinstance(docs, list) or not docs or len(docs) != len(set(docs)):
        raise ValueError('maintained_docs must be a unique nonempty list')
    for doc in docs:
        local_file(repo, normalized(doc))
        if not doc.endswith('.md') or doc == 'docs/context-map.md':
            raise ValueError('maintained_docs contains an invalid document')
    if not isinstance(routes, list) or not routes:
        raise ValueError('routes must be a nonempty list')
    prefixes = set()
    for route in routes:
        if not isinstance(route, dict) or set(route) != {'prefix', 'docs'}:
            raise ValueError('Invalid route schema')
        prefix = normalized(route['prefix'].rstrip('/'))
        if prefix in prefixes:
            raise ValueError('Duplicate routing prefix')
        prefixes.add(prefix)
        if not isinstance(route['docs'], list) or not route['docs'] or len(route['docs']) != len(set(route['docs'])) or any(doc not in docs for doc in route['docs']):
            raise ValueError('Route must reference unique maintained documents')
    return config


def check(repo):
    config = load_policy(repo)
    content = local_file(repo, 'docs/context-map.md').read_text()
    links = [doc_link(repo, href, 'docs') for href in LINK.findall(content)]
    if len(links) != len(set(links)):
        raise ValueError('Duplicate context-map index entries')
    if len(content.splitlines()) > 100 or len(content) > 12000:
        raise ValueError('Context map must remain a compact index')
    rows = {}
    for line in content.splitlines():
        cells = [cell.strip() for cell in line.strip().strip('|').split('|')]
        if line.startswith('|') and len(cells) == 3 and LINK.search(cells[0]):
            doc = doc_link(repo, LINK.search(cells[0])[1], 'docs')
            if not cells[1] or not cells[2]:
                raise ValueError('Indexed documents require purpose and change triggers')
            rows[doc] = cells
    if set(config['maintained_docs']) != set(rows):
        raise ValueError('Maintained documents and purpose/trigger table must match')
    # Repeating the full maintained index in another document creates competing maps.
    for doc in config['maintained_docs']:
        text = local_file(repo, doc).read_text()
        linked = set()
        for href in LINK.findall(text):
            try:
                linked.add(doc_link(repo, href, str(PurePosixPath(doc).parent)))
            except ValueError:
                pass
        if len(config['maintained_docs']) > 2 and set(config['maintained_docs']).issubset(linked):
            raise ValueError(f'Duplicate full documentation index: {doc}')
    xml = local_file(repo, 'config/checkstyle/business-catch-suppressions.xml').read_text()
    if '<!ENTITY' in xml.upper():
        raise ValueError('Suppression entities forbidden')
    tree = ET.fromstring(xml)
    if tree.tag != 'suppressions' or tree.attrib:
        raise ValueError('Expected plain suppressions root')
    for item in tree:
        if list(item) or item.tag != 'suppress' or set(item.attrib) != {'id', 'files', 'lines'} or item.get('id') != 'BusinessCatch':
            raise ValueError('Only BusinessCatch file/line suppressions are allowed')
        pattern = item.get('files', '')
        # Checkstyle supplies absolute paths. Match a separator-delimited exact suffix.
        separator = r'[\\/]'
        if not pattern.startswith(separator) or not pattern.endswith(r'\.java$'):
            raise ValueError('Suppression files must be an exact portable source-path suffix')
        path = pattern[len(separator):-len(r'\.java$')].replace(separator, '/') + '.java'
        if not re.fullmatch(r'src/(?:[A-Za-z0-9_-]+/)+[A-Za-z0-9_]+\.java', path):
            raise ValueError('Suppression files must name one exact Java source path')
        canonical = separator + path[:-len('.java')].replace('/', separator) + r'\.java$'
        if pattern != canonical:
            raise ValueError('Suppression files must use canonical portable separators')
        local_file(repo, path)
        lines = item.get('lines', '')
        if not re.fullmatch(r'[1-9]\d*(?:-[1-9]\d*)?(?:,[1-9]\d*(?:-[1-9]\d*)?)*', lines):
            raise ValueError('Suppression requires bounded lines')
        count = 0
        for span in lines.split(','):
            values = list(map(int, span.split('-')))
            first, last = values[0], values[-1]
            if last < first:
                raise ValueError('Reversed suppression range')
            count += last - first + 1
        if count > 20:
            raise ValueError('Suppression cannot cover more than 20 lines')
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='.')
    args = parser.parse_args()
    try:
        check(Path(args.repo).resolve())
    except (ValueError, OSError, TypeError, KeyError, ET.ParseError) as error:
        print(f'Harness policy: {error}', file=sys.stderr)
        return 1
    print('Harness policy accepted; semantic review remains required.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
