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

    def test_candidate_routing_and_durable_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {
                'README.md': 'Read me',
                'docs/repository.md': 'Review policy',
                'docs/context-map.md': '| [Readme](../README.md) | Start | Commands |\n| [Repository](repository.md) | Reviews | Policy |\n',
                'docs/adr/0003-policy.md': 'Decision',
                'config/checkstyle/business-catch-suppressions.xml': '<suppressions/>',
                'config/harness/doc-policy.json': json.dumps({'version': 1, 'maintained_docs': ['README.md', 'docs/repository.md'], 'routes': [{'prefix': 'scripts/', 'docs': ['docs/repository.md']}]})
            }
            for path, content in files.items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            good = example()
            self.assertEqual([], policy.validate('chore: review', good, root, ['scripts/check.py'], baseline_root=root))
            original = '[Repository policy](docs/repository.md)'
            reason = 'No documentation impact: Test data changes only; review behavior is unchanged.\nReviewed: ' + original
            self.assertEqual([], policy.validate('chore: review', good.replace(original, reason), root, ['scripts/test.py'], baseline_root=root))
            for blank in ('', ' ', '\t', '\r'):
                missing_reason = 'No documentation impact:' + blank + '\nReviewed: ' + original
                self.assertIn('No documentation impact needs a concrete reason',
                              policy.validate('chore: review', good.replace(original, missing_reason), root, ['scripts/test.py'], baseline_root=root))
            for link in ('[Readme](README.md)', '[Missing](docs/missing.md)', '[Remote](https://example.org/docs/repository.md)', '[Escape](../docs/repository.md)'):
                self.assertTrue(policy.validate('chore: review', good.replace(original, link), root, ['scripts/test.py'], baseline_root=root))
            self.assertTrue(policy.validate('chore: review', good, root, ['../bad'], baseline_root=root))
            self.assertTrue(policy.validate('chore: review', good, root, None, baseline_root=root))
            durable = good.replace('## Durable decision\n\nno', '## Durable decision\n\nyes').replace(original, original + ' [ADR](docs/adr/0003-policy.md) [Map](docs/context-map.md)')
            self.assertTrue(policy.validate('chore: review', durable, root, ['scripts/test.py'], baseline_root=root))
            self.assertEqual([], policy.validate('chore: review', durable, root, ['scripts/test.py', 'docs/context-map.md', 'docs/adr/0003-policy.md'], baseline_root=root))

    def test_candidate_routes_cannot_weaken_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            baseline, candidate = Path(directory) / 'base', Path(directory) / 'head'
            for root in (baseline, candidate):
                (root / 'config/harness').mkdir(parents=True)
                (root / 'config/checkstyle').mkdir(parents=True)
                (root / 'docs').mkdir()
                (root / 'README.md').write_text('Intro')
                (root / 'docs/repository.md').write_text('Reviews')
                (root / 'docs/context-map.md').write_text('| [Intro](../README.md) | Start | Commands |\n| [Review](repository.md) | Reviews | Rules |\n')
                (root / 'config/checkstyle/business-catch-suppressions.xml').write_text('<suppressions/>')
            config = {'version': 1, 'maintained_docs': ['README.md', 'docs/repository.md'], 'routes': [{'prefix': 'scripts/', 'docs': ['docs/repository.md']}, {'prefix': 'build.gradle', 'docs': ['docs/repository.md']}]}
            (baseline / 'config/harness/doc-policy.json').write_text(json.dumps(config))
            body = example().replace('[Repository policy](docs/repository.md)', '[Intro](README.md)')
            for routes in ([{'prefix': 'unrelated/', 'docs': ['README.md']}], [{'prefix': 'scripts/', 'docs': ['README.md']}]):
                config['routes'] = routes
                (candidate / 'config/harness/doc-policy.json').write_text(json.dumps(config))
                errors = policy.validate('chore: review', body, candidate, ['scripts/check.py'], baseline_root=baseline)
                self.assertTrue(any('docs/repository.md' in error for error in errors), errors)
            self.assertEqual([], policy.validate('chore: review', body, candidate, ['build.gradle.evil'], baseline_root=baseline))
            self.assertTrue(policy.validate('chore: review', body, candidate, ['build.gradle'], baseline_root=baseline))

    def test_workflow_excludes_base_only_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / 'candidate'
            candidate.mkdir()
            fixture_env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
            def git(*args):
                return subprocess.check_output(['git', '-C', str(candidate), *args], env=fixture_env, stderr=subprocess.DEVNULL).decode().strip()
            git('init')
            git('config', 'user.name', 'Policy fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            (candidate / 'common').write_text('initial')
            git('add', '.')
            git('commit', '-m', 'common')
            common = git('rev-parse', 'HEAD')
            (candidate / 'base-only').write_text('base change')
            git('add', '.')
            git('commit', '-m', 'advance base')
            base = git('rev-parse', 'HEAD')
            git('checkout', '--detach', common)
            (candidate / 'head-only').write_text('PR change')
            git('add', '.')
            git('commit', '-m', 'advance head')
            head = git('rev-parse', 'HEAD')
            baseline = root / 'baseline/scripts'
            baseline.mkdir(parents=True)
            (baseline / 'check-pr-policy.py').write_text('# trusted checker exists')
            workflow = (ROOT / '.github/workflows/pr-policy.yml').read_text()
            script = workflow.split("          python3 - <<'PY'\n", 1)[1].split('\n          PY', 1)[0]
            script = '\n'.join(line[10:] for line in script.splitlines())
            subprocess.run([sys.executable, '-c', script], cwd=root,
                           env={**fixture_env, 'BASE_SHA': base, 'HEAD_SHA': head}, check=True)
            self.assertEqual(['head-only'], json.loads((root / 'changed-files.json').read_text()))

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
