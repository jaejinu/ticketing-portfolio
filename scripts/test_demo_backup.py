import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


backup = load('backup_test', 'demo-backup.py')
remote = load('remote_test', 'demo-remote-backup.py')


class RecoverySafetyTests(unittest.TestCase):
    def test_corruption_rejected_before_creating_database(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            (target / 'database.dump').write_bytes(b'corrupt')
            (target / 'manifest.json').write_text(json.dumps({'sha256': {'database.dump': 'wrong'}}))
            with patch.object(backup, 'sql') as sql:
                with self.assertRaisesRegex(RuntimeError, 'checksum'):
                    backup.verify(target)
                sql.assert_not_called()

    def test_path_escape_rejected_before_database_access(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            (target / 'manifest.json').write_text(json.dumps({'sha256': {'../outside': 'ignored'}}))
            with patch.object(backup, 'sql') as sql:
                with self.assertRaisesRegex(RuntimeError, 'checksum'):
                    backup.verify(target)
                sql.assert_not_called()

    def test_failed_restore_always_drops_only_temporary_database(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            (target / 'database.dump').write_bytes(b'dump')
            (target / 'manifest.json').write_text(json.dumps({'sha256': {}}))
            with patch.object(backup, 'sql', return_value='') as sql, patch.object(
                    backup.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'pg_restore')):
                with self.assertRaises(subprocess.CalledProcessError):
                    backup.verify(target)
                statements = [call.args[1] for call in sql.call_args_list]
                self.assertTrue(statements[0].startswith('CREATE DATABASE ticketing_restore_'))
                self.assertEqual(statements[-1], statements[0].replace('CREATE DATABASE', 'DROP DATABASE') + ' WITH (FORCE)')
                self.assertNotIn('DROP DATABASE ticketing_public_demo', statements)

    def test_public_remote_is_rejected_before_reading_snapshot_or_key(self):
        with patch.object(remote, 'run', return_value=subprocess.CompletedProcess([], 0, 'false\n')) as run:
            with self.assertRaisesRegex(RuntimeError, 'must be private'):
                remote.upload(Path('/does-not-exist'))
            self.assertEqual(run.call_count, 1)

    def test_backend_resumed_even_when_unpause_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(backup.demo, 'BACKUP_DIR', Path(directory)), \
                 patch.object(backup.demo, 'running', return_value=True), \
                 patch.object(backup.demo, 'stop'), \
                 patch.object(backup.demo, 'docker', side_effect=[None, RuntimeError('copy failed'), RuntimeError('unpause failed')]), \
                 patch.object(backup, 'resume_backend') as resume:
                with self.assertRaisesRegex(RuntimeError, 'unpause failed'):
                    backup.create()
                resume.assert_called_once_with(True)


if __name__ == '__main__':
    unittest.main()
