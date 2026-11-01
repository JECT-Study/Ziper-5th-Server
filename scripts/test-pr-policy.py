#!/usr/bin/env python3
"""Adversarial policy examples, without third-party dependencies."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('policy', ROOT / 'scripts/check-pr-policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


def example(kind='chore'):
    text = (ROOT / f'.github/PULL_REQUEST_TEMPLATE/{kind}.md').read_text()
    import re
    text = re.sub(r'<!--.*?-->', '', text, flags=re.S)
    sections = {
        'Intent': 'Require a documented PR review before merge.',
        'Validation': '`python3 scripts/test-pr-policy.py` passed locally.',
        'Documentation': '[Repository policy](docs/repository.md)',
        'User story': 'As a reviewer, I can see the evidence before approving.',
        'Observable behavior': 'A missing validation section fails the PR policy check.',
        'E2E evidence': 'Submitted an incomplete PR body; checker returned exit code 1.',
        'Implementation details': 'Parse the event JSON in Python without shell interpolation.',
        'Reproduction': 'Submit a PR with no validation evidence.',
        'Before and after': 'Before: accepted; after: rejected with a missing-content error.',
        'Purpose and impact': 'Make review requirements visible to authors and reviewers.',
    }
    for name, content in sections.items():
        text = re.sub(r'(^## ' + re.escape(name) + r'\n)\s*(?=## |\Z)', lambda m: m[1] + '\n' + content + '\n\n', text, flags=re.M)
    return text.replace('- [ ]', '- [x]')


class PolicyTests(unittest.TestCase):
    def test_completed_templates(self):
        for kind in policy.SPECIFIC:
            with self.subTest(kind=kind):
                self.assertEqual([], policy.validate(f'{kind}: enforce review policy', example(kind)))

    def test_unfilled_templates_and_chooser_fail(self):
        for path in [ROOT / '.github/PULL_REQUEST_TEMPLATE.md', *sorted((ROOT / '.github/PULL_REQUEST_TEMPLATE').glob('*.md'))]:
            self.assertTrue(policy.validate('chore: review', path.read_text()), path)

    def test_blank_placeholder_wrong_type_and_title(self):
        good = example()
        for replacement in ('', '...', '-', 'TODO', 'TBD', 'N/A', '<specific reason>', '<!-- evidence -->'):
            body = good.replace('Require a documented PR review before merge.', replacement)
            self.assertTrue(policy.validate('chore: review', body), replacement)
        for title in ('feat: review', 'fix: review', 'feature: review', 'chore:', ''):
            self.assertTrue(policy.validate(title, good), title)
        self.assertTrue(policy.validate('chore: review', good.replace('\nchore\n', '\nother\n')))

    def test_attestations_required_not_comments_or_code(self):
        good = example()
        for check in policy.CHECKS:
            line = '- [x] ' + check
            for replacement in ('- [ ] ' + check, '<!-- ' + line + ' -->', '```\n' + line + '\n```'):
                self.assertTrue(policy.validate('chore: review', good.replace(line, replacement)))

    def test_feature_sections_and_duplicate_contract(self):
        good = example('feature')
        for heading in policy.SPECIFIC['feature']:
            self.assertTrue(policy.validate('feature: review', good.replace('## ' + heading, '## Other')))
        self.assertTrue(policy.validate('feature: review', good + '\n## Type\nfeature\n'))

    def test_documentation_and_durable_decision(self):
        good = example()
        original = '[Repository policy](docs/repository.md)'
        no_impact = 'No documentation impact: Only the test fixture changes; documented behavior is unchanged.'
        self.assertEqual([], policy.validate('chore: review', good.replace(original, no_impact)))
        for value in ('No documentation impact:', 'No documentation impact: TODO', '[Link](https://example.org)', original + '\n' + no_impact):
            self.assertTrue(policy.validate('chore: review', good.replace(original, value)))
        durable = good.replace('## Durable decision\n\nno', '## Durable decision\n\nyes')
        links = '[ADR](docs/adr/0003-policy.md) [Map](docs/context-map.md)'
        self.assertEqual([], policy.validate('chore: review', durable.replace(original, links)))
        for value in (no_impact, '[ADR](docs/adr/0003-policy.md)', '[Map](docs/context-map.md)', original):
            self.assertTrue(policy.validate('chore: review', durable.replace(original, value)))

    def test_event_json_entrypoint_and_hostile_text_are_data(self):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / 'event.json'
            marker = Path(directory) / 'executed'
            body = example() + f'\nUntrusted text: $(touch {marker}) `touch {marker}`\n'
            event.write_text(json.dumps({'pull_request': {'title': 'chore: review', 'body': body}}))
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/check-pr-policy.py')], env={**os.environ, 'GITHUB_EVENT_PATH': str(event)}, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(marker.exists())
            event.write_text(json.dumps({'pull_request': {'title': 'chore: review', 'body': None}}))
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/check-pr-policy.py')], env={**os.environ, 'GITHUB_EVENT_PATH': str(event)}, capture_output=True)
            self.assertEqual(1, result.returncode)


if __name__ == '__main__':
    unittest.main()
