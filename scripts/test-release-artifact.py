#!/usr/bin/env python3
"""Negative tests for release identity without Docker or credentials."""
import importlib.util
import json
import pathlib
import tempfile
import unittest
import uuid
import zipfile

spec = importlib.util.spec_from_file_location('verifier', pathlib.Path(__file__).with_name('verify-release-artifact.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ReleaseIdentityTest(unittest.TestCase):
    def test_matching_and_tampered_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            jar, image, bundles = root / 'release.jar', root / 'image.jar', root / 'bundles'
            bundles.mkdir()
            debug_id = str(uuid.uuid4())
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/classes/sentry-debug-meta.properties',
                                 'io.sentry.bundle-ids=' + debug_id)
            image.write_bytes(jar.read_bytes())
            bundle = bundles / 'arbitrary-name.zip'
            def write_bundle(value):
                with zipfile.ZipFile(bundle, 'w') as archive:
                    archive.writestr('manifest.json', json.dumps({'debug_id': value}))
            write_bundle(debug_id)
            verifier.verify(jar, image, bundles)
            stale_bundle = bundles / 'stale.zip'
            with zipfile.ZipFile(stale_bundle, 'w') as archive:
                archive.writestr('manifest.json', json.dumps({'debug_id': str(uuid.uuid4())}))
            verifier.verify(jar, image, bundles)
            duplicate = bundles / 'duplicate-current.zip'
            duplicate.write_bytes(bundle.read_bytes())
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                verifier.verify(jar, image, bundles)
            duplicate.unlink()
            image.write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                verifier.verify(jar, image, bundles)
            image.write_bytes(jar.read_bytes())
            write_bundle(str(uuid.uuid4()))
            with self.assertRaisesRegex(ValueError, 'debug ID'):
                verifier.verify(jar, image, bundles)
            bundle.unlink()
            stale_bundle.unlink()
            with self.assertRaisesRegex(ValueError, 'No Sentry'):
                verifier.verify(jar, image, bundles)


if __name__ == '__main__':
    unittest.main()
