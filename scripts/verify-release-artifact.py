#!/usr/bin/env python3
"""Verify image bytes and the Sentry source context belonging to those bytes."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import tempfile
import uuid
import zipfile


def digest(path):
    checksum = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.hexdigest()


def verify(jar, image_jar, bundle_dir):
    if digest(jar) != digest(image_jar):
        raise ValueError('Image JAR SHA256 differs from the release JAR')
    with zipfile.ZipFile(jar) as archive:
        metadata = archive.read('BOOT-INF/classes/sentry-debug-meta.properties').decode()
    properties = dict(line.split('=', 1) for line in metadata.splitlines()
                      if '=' in line and not line.startswith('#'))
    ids = {str(uuid.UUID(value.strip()))
           for value in properties['io.sentry.bundle-ids'].split(',')}
    if not ids:
        raise ValueError('Missing Sentry bundle IDs')
    found = set()
    bundles = sorted(pathlib.Path(bundle_dir).rglob('*.zip'))
    if not bundles:
        raise ValueError('No Sentry source bundles found')
    for bundle in bundles:
        with zipfile.ZipFile(bundle) as archive:
            manifest = json.loads(archive.read('manifest.json'))
        debug_id = str(uuid.UUID(manifest['debug_id']))
        if debug_id not in ids:
            continue
        if debug_id in found:
            raise ValueError('Duplicate Sentry source bundle ID')
        found.add(debug_id)
    if found != ids:
        raise ValueError('Sentry source bundle debug ID required by JAR metadata is missing')
    return digest(jar)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', default='build/libs/ziper-server.jar')
    parser.add_argument('--bundles', default='build/intermediates/sentry/java/source-bundle')
    parser.add_argument('--image', required=True)
    args = parser.parse_args()
    container = subprocess.check_output(['docker', 'create', args.image], text=True).strip()
    try:
        with tempfile.TemporaryDirectory() as temporary:
            extracted = pathlib.Path(temporary) / 'app.jar'
            subprocess.run(['docker', 'cp', container + ':/app/app.jar', str(extracted)], check=True)
            checksum = verify(args.jar, extracted, args.bundles)
            print('Release image JAR SHA256 and Sentry debug IDs verified: ' + checksum)
    finally:
        subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
