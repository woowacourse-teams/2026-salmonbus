"""Offline libpq parsing checks; uses made-up credentials and never opens a DB connection."""
import ctypes.util
import unittest
from unittest.mock import patch

from prepare_postgres import connection_environment


class ConnectionEnvironmentTest(unittest.TestCase):
    @unittest.skipUnless(ctypes.util.find_library('pq'), 'libpq required')
    def test_uri_preserves_escaped_credentials_and_tls(self):
        env = connection_environment(
            'postgresql://monitor:p%40ss%3Aword@localhost:5433/salmonbus'
            '?sslmode=verify-full&sslrootcert=%2Ftmp%2Frds.pem')
        self.assertEqual('p@ss:word', env['PGPASSWORD'])
        self.assertEqual('monitor', env['PGUSER'])
        self.assertEqual('localhost', env['PGHOST'])
        self.assertEqual('5433', env['PGPORT'])
        self.assertEqual('salmonbus', env['PGDATABASE'])
        self.assertEqual('verify-full', env['PGSSLMODE'])
        self.assertEqual('/tmp/rds.pem', env['PGSSLROOTCERT'])

    @unittest.skipUnless(ctypes.util.find_library('pq'), 'libpq required')
    def test_key_value_format_preserves_spaces_and_escaped_quote(self):
        env = connection_environment(
            "host=localhost dbname=salmonbus user=monitor password='a b\\'c'")
        self.assertEqual("a b'c", env['PGPASSWORD'])
        self.assertEqual('salmonbus', env['PGDATABASE'])

    @unittest.skipUnless(ctypes.util.find_library('pq'), 'libpq required')
    def test_invalid_dsn_does_not_disclose_value(self):
        with self.assertRaises(RuntimeError) as failure:
            connection_environment('private-token invalid-option=secret')
        self.assertNotIn('private-token', str(failure.exception))
        self.assertNotIn('secret', str(failure.exception))

    @unittest.skipUnless(ctypes.util.find_library('pq'), 'libpq required')
    def test_nul_does_not_silently_truncate_dsn(self):
        with self.assertRaises(RuntimeError):
            connection_environment('dbname=salmonbus\0host=elsewhere')

    def test_missing_library_fails_without_fallback(self):
        with patch('ctypes.util.find_library', return_value=None):
            with self.assertRaisesRegex(RuntimeError, 'libpq is required'):
                connection_environment('password=not-a-real-secret')


if __name__ == '__main__':
    unittest.main()
