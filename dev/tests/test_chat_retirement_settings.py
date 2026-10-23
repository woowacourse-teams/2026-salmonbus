"""Existing local credentials survive chat retirement without a Mongo service."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SETTINGS = Path(__file__).resolve().parents[1] / 'docker/settings.sh'


class ChatRetirementSettingsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / 'settings'

    def prepare(self, success=True):
        result = subprocess.run(
            ['bash', '-euo', 'pipefail', '-c',
             'umask 077; source "$1"; LOCAL_DIR="$2"; LOCAL_ENV_DIR="$LOCAL_DIR/env"; init_settings',
             'settings-check', str(SETTINGS), str(self.directory)],
            capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode == 0, success)

    def hashes(self):
        return {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                for p in (self.directory / 'env').iterdir() if p.is_file()}

    def legacy(self, valid=True):
        self.prepare()
        mongo = self.directory / 'env/mongodb.env'
        mongo.write_text('MONGO_INITDB_ROOT_USERNAME=salmonbus_local\n'
                         'MONGO_INITDB_ROOT_PASSWORD=' + 'a' * 48 + '\n')
        os.chmod(mongo, 0o600)
        api = self.directory / 'env/api.env'
        uri = ('mongodb://salmonbus_local:' + 'a' * 48
               + '@mongodb:27017/salmonbus_chat?authSource=admin') if valid else 'invalid'
        with api.open('a') as stream:
            stream.write('CHAT_MONGODB_URI=' + uri + '\n')
        return api

    def test_fresh_settings_start_and_repeat_without_mongo_settings(self):
        self.prepare()
        self.assertEqual(set(self.hashes()), {'postgres.env', 'api.env', 'worker.env'})
        before = self.hashes()
        self.prepare()
        self.assertEqual(before, self.hashes())

    def test_legacy_settings_keep_passwords_and_retired_mongo_files(self):
        api = self.legacy()
        before = self.hashes()
        database_lines = [line for line in api.read_text().splitlines() if line.startswith('DB_')]
        self.prepare()
        self.assertEqual(database_lines, api.read_text().splitlines())
        after = self.hashes()
        for name in ('postgres.env', 'worker.env', 'mongodb.env'):
            self.assertEqual(before[name], after[name])
        self.prepare()
        self.assertEqual(after, self.hashes())

    def test_invalid_legacy_settings_fail_before_rewriting_credentials(self):
        self.legacy(valid=False)
        before = self.hashes()
        self.prepare(success=False)
        self.assertEqual(before, self.hashes())


if __name__ == '__main__':
    unittest.main()
