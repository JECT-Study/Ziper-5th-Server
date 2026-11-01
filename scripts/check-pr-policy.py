#!/usr/bin/env python3
"""Check PR structure only; reviewers judge evidence and documentation semantics."""
import json
import os
from pathlib import Path
import re
import sys

CHECKS = (
    'Documentation synchronized or no-impact reason recorded',
    'Validation evidence recorded',
)
SPECIFIC = {
    'feature': ('User story', 'Observable behavior', 'E2E evidence', 'Implementation details'),
    'bug': ('Reproduction', 'Before and after'),
    'chore': ('Purpose and impact',),
}


def substantive(value):
    value = re.sub(r'<!--.*?-->', '', value, flags=re.S).strip()
    if not value or not any(character.isalnum() for character in value):
        return False
    return not re.fullmatch(
        r'[\s\W]*(?:todo|tbd|n/?a|none|placeholder|fill(?: in)?|작성 예정|추후 작성)[\s\W]*',
        value, re.I,
    ) and not re.fullmatch(r'<[^>]+>', value)


def validate(title, body):
    errors = []
    body = re.sub(r'<!--.*?-->', '', body or '', flags=re.S)
    # Code examples are evidence, never declarations or checked attestations.
    contract = re.sub(r'^```[^\n]*\n.*?^```[^\n]*$', '', body, flags=re.M | re.S)
    sections = {}
    for match in re.finditer(r'^## ([^\n]+)\n(.*?)(?=^## |\Z)', contract, re.M | re.S):
        name, value = match.group(1).strip(), match.group(2).strip()
        if name in sections:
            errors.append(f'Duplicate section: {name}')
        sections[name] = value
    kind = sections.get('Type', '')
    if kind not in SPECIFIC:
        errors.append('Type must be exactly feature, bug, or chore')
    if not re.fullmatch(r'(feature|bug|chore):\s+\S.*', title or ''):
        errors.append('Title must start with feature:, bug:, or chore: and a description')
    elif title.split(':', 1)[0] != kind:
        errors.append('Title type must match Type')
    for name in ('Intent', 'Validation', 'Documentation') + SPECIFIC.get(kind, ()):
        if not substantive(sections.get(name, '')):
            errors.append(f'Missing concrete content: {name}')
    checklist = sections.get('Checklist', '')
    for check in CHECKS:
        if not re.search(r'^- \[[xX]\] ' + re.escape(check) + r'\s*$', checklist, re.M):
            errors.append(f'Required checked attestation: {check}')
    docs = sections.get('Documentation', '')
    links = re.findall(r'\[[^\]]+\]\(([^\s)]+)\)', docs)
    doc_links = [link for link in links if re.search(r'(?:^|/)docs/[^?#]+\.md(?:[?#].*)?$', link) or re.search(r'(?:^|/)(?:README|AGENTS)\.md(?:[?#].*)?$', link)]
    no_impact = re.search(r'^No documentation impact:\s*(.*)$', docs, re.M)
    if no_impact:
        if not substantive(no_impact.group(1)):
            errors.append('No documentation impact needs a concrete reason')
        if doc_links:
            errors.append('Choose documentation links or no-impact reason, not both')
    elif not doc_links:
        errors.append('Documentation needs Markdown doc links or No documentation impact: reason')
    durable = sections.get('Durable decision', '')
    if durable not in ('yes', 'no'):
        errors.append('Durable decision must be exactly yes or no')
    if durable == 'yes':
        if no_impact:
            errors.append('Durable decisions cannot declare no documentation impact')
        if not any(re.search(r'(?:^|/)docs/adr/[^/]+\.md(?:[?#].*)?$', link) for link in links):
            errors.append('Durable decisions require an ADR link')
        if not any(re.search(r'(?:^|/)docs/context-map\.md(?:[?#].*)?$', link) for link in links):
            errors.append('Durable decisions require a context-map link')
    return errors


def main():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    pr = event['pull_request']
    errors = validate(pr.get('title', ''), pr.get('body', ''))
    if errors:
        for error in errors:
            print(f'PR policy: {error}', file=sys.stderr)
        return 1
    print('PR structure accepted; human review must verify evidence and documentation accuracy.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
