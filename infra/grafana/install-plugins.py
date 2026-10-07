"""Install checksum-pinned, officially signed plugin packages for the target arch."""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

arch = os.environ['TARGETARCH']
rows = [r for r in json.loads(Path('/build/plugins.lock.json').read_text()) if r['arch'] == arch]
assert len(rows) == 6, 'Unsupported architecture'
for row in rows:
    data = urllib.request.urlopen(row['url'], timeout=120).read()
    assert hashlib.sha256(data).hexdigest() == row['sha256'], row['plugin']
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for item in archive.infolist():
                target = (root / item.filename).resolve()
                assert target.is_relative_to(root), 'Unsafe archive path'
                archive.extract(item, root)
                mode = (item.external_attr >> 16) & 0o777
                if mode:
                    target.chmod(mode)
        matches = [p.parent for p in root.rglob('plugin.json')
                   if json.loads(p.read_text()).get('id') == row['plugin']]
        assert len(matches) == 1
        source = matches[0]
        assert (source / 'MANIFEST.txt').is_file(), 'Missing signed manifest'
        assert json.loads((source / 'plugin.json').read_text())['info']['version'] == row['version']
        shutil.copytree(source, Path('/out') / row['plugin'])
    print(row['plugin'], row['version'], flush=True)
