#!/usr/bin/env python3
"""Upload an age-encrypted verified snapshot to the existing PRIVATE GitHub repository."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile

spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('demo-backup.py'))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
REPO = 'jaejinu/ticketing'
KEY = backup.demo.RUNTIME / 'backup-age.key'


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs)


def upload(snapshot):
    # Refuse public destinations before even preparing an upload.
    private = run('gh', 'api', f'repos/{REPO}', '--jq', '.private', text=True).stdout.strip()
    if private != 'true':
        raise RuntimeError('Remote backup destination must be private')
    snapshot = snapshot.resolve()
    result = json.loads((snapshot / 'restore-result.json').read_text())
    if result['status'] != 'passed':
        raise RuntimeError('An isolated restore must pass before upload')
    manifest = json.loads((snapshot / 'manifest.json').read_text())
    for name, expected in manifest['sha256'].items():
        path = (snapshot / name).resolve()
        if not path.is_relative_to(snapshot) or backup.digest(path) != expected:
            raise RuntimeError('Snapshot changed after verification')
    if not KEY.exists():
        run('age-keygen', '-o', str(KEY))
    KEY.chmod(0o600)
    recipient = run('age-keygen', '-y', str(KEY), text=True).stdout.strip()
    tag = 'demo-backup-' + snapshot.name.removeprefix('snapshot-')
    with tempfile.TemporaryDirectory(prefix='ticketing-encrypted-backup-') as directory:
        directory = Path(directory)
        archive = directory / 'snapshot.tar'
        encrypted = directory / 'snapshot.tar.age'
        with tarfile.open(archive, 'w') as tar:
            tar.add(snapshot, arcname='snapshot')
        run('age', '-r', recipient, '-o', str(encrypted), str(archive))
        run('gh', 'release', 'create', tag, str(encrypted), '--repo', REPO, '--prerelease',
            '--title', 'Encrypted demo backup ' + snapshot.name,
            '--notes', 'age-encrypted DB + Mock PG snapshot. Recovery key is stored offline from this repository.')
        downloaded = directory / 'downloaded'
        run('gh', 'release', 'download', tag, '--repo', REPO, '--dir', str(downloaded), '--pattern', 'snapshot.tar.age')
        if backup.digest(encrypted) != backup.digest(downloaded / encrypted.name):
            raise RuntimeError('Remote round-trip checksum mismatch')
        restored = directory / 'roundtrip.tar'
        run('age', '-d', '-i', str(KEY), '-o', str(restored), str(downloaded / encrypted.name))
        if backup.digest(archive) != backup.digest(restored):
            raise RuntimeError('Decrypted remote archive differs')
    print(json.dumps({'repository': REPO, 'tag': tag, 'encrypted': True, 'downloadAndDecrypt': 'passed'}))
    (snapshot / 'remote-result.json').write_text(json.dumps({'tag': tag, 'repository': REPO, 'verified': True}) + '\n')


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', type=Path)
    upload(parser.parse_args().snapshot)
