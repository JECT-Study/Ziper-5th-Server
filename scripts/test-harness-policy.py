#!/usr/bin/env python3
"""Exercise policy rejection using isolated temporary repository fixtures."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('harness', ROOT / 'scripts/check-harness-policy.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class HarnessTests(unittest.TestCase):
    def fixture(self, root):
        files = {'README.md': 'Start here.', 'docs/a.md': 'Guide.', 'docs/context-map.md': '| [Start](../README.md) | Start | Commands change |\n| [Guide](a.md) | Guide | Rules change |\n', 'config/checkstyle/business-catch-suppressions.xml': '<suppressions/>', 'config/harness/doc-policy.json': json.dumps({'version': 1, 'maintained_docs': ['README.md', 'docs/a.md'], 'routes': [{'prefix': 'src/', 'docs': ['docs/a.md']}]})}
        for path, text in files.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)

    def test_valid_and_fail_closed(self):
        for fault in ('valid', 'valid-suppression', 'missing', 'traversal', 'symlink', 'duplicate', 'duplicate-index', 'schema', 'broad', 'allchecks', 'unbounded'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.fixture(root)
                context = root / 'docs/context-map.md'
                suppression = root / 'config/checkstyle/business-catch-suppressions.xml'
                if fault == 'valid-suppression':
                    source = root / 'src/main/java/A.java'
                    source.parent.mkdir(parents=True)
                    source.write_text('class A {}')
                    suppression.write_text('<suppressions><suppress id="BusinessCatch" files="' + r'[\\/]src[\\/]main[\\/]java[\\/]A\.java$' + '" lines="1"/></suppressions>')
                elif fault == 'missing':
                    (root / 'docs/a.md').unlink()
                elif fault == 'traversal':
                    context.write_text('[Escape](../../outside.md)')
                elif fault == 'symlink':
                    (root / 'docs/a.md').unlink()
                    (root / 'docs/a.md').symlink_to(root / 'README.md')
                elif fault == 'duplicate':
                    context.write_text(context.read_text() * 2)
                elif fault == 'duplicate-index':
                    (root / 'docs/b.md').write_text('More guidance')
                    context.write_text(context.read_text() + '| [More](b.md) | More | Contract changes |\n')
                    config_path = root / 'config/harness/doc-policy.json'
                    config = json.loads(config_path.read_text())
                    config['maintained_docs'].append('docs/b.md')
                    config_path.write_text(json.dumps(config))
                    (root / 'README.md').write_text('[Self](README.md) [Guide](docs/a.md) [More](docs/b.md)')
                elif fault == 'schema':
                    (root / 'config/harness/doc-policy.json').write_text('{}')
                elif fault == 'broad':
                    suppression.write_text('<suppressions><suppress id="BusinessCatch" files=".*" lines="1-2"/></suppressions>')
                elif fault == 'allchecks':
                    suppression.write_text('<suppressions><suppress checks=".*" files=".*"/></suppressions>')
                elif fault == 'unbounded':
                    source = root / 'src/main/java/A.java'
                    source.parent.mkdir(parents=True)
                    source.write_text('class A {}')
                    suppression.write_text('<suppressions><suppress id="BusinessCatch" files="' + r'[\\/]src[\\/]main[\\/]java[\\/]A\.java$' + '" lines="1-999"/></suppressions>')
                if fault in ('valid', 'valid-suppression'):
                    harness.check(root)
                elif fault == 'unbounded':
                    with self.assertRaisesRegex(ValueError, 'cannot cover more than 20 lines'):
                        harness.check(root)
                else:
                    with self.assertRaises((ValueError, OSError)):
                        harness.check(root)


if __name__ == '__main__':
    unittest.main()
