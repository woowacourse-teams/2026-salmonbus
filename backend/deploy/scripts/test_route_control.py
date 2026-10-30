import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('route_control', Path(__file__).with_name('route-control.py'))
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)


class RouteControlTest(unittest.TestCase):
    def test_service_off_uses_current_revision_and_preserves_collection(self):
        args = control.parse(['4000', 'service', 'off'])
        command = control.change_arguments(args, '200000314',
            {'revision': 17, 'collectionEnabled': True, 'serviceRequested': True}, 'operator-a', 'request-a')
        self.assertEqual(command[0], 'routes-service')
        self.assertEqual(command[command.index('--expected-revision') + 1], '17')
        self.assertEqual(command[command.index('--enabled') + 1], 'false')
        self.assertEqual(command[command.index('--actor') + 1], 'operator-a')

    def test_collection_off_requires_explicit_both_when_service_requested(self):
        with self.assertRaisesRegex(ValueError, 'both off'):
            control.change_arguments(control.parse(['5600', 'collection', 'off']), '228000184',
                {'revision': 1, 'serviceRequested': True}, 'operator', 'request')

    def test_both_off_has_no_enabled_argument(self):
        command = control.change_arguments(control.parse(['5600', 'both', 'off']), '228000184',
            {'revision': 2}, 'operator', 'request')
        self.assertEqual(command[0], 'routes-disable-both')
        self.assertNotIn('--enabled', command)

    def test_service_on_does_not_implicitly_enable_collection(self):
        with self.assertRaisesRegex(ValueError, 'collection on'):
            control.change_arguments(control.parse(['5600', 'service', 'on']), '228000184',
                {'revision': 1, 'collectionEnabled': False}, 'operator', 'request')

    def test_wrong_database_is_rejected_before_mutation(self):
        with patch.object(control, 'invoke', return_value={
                'target': 'ACADEMY', 'databaseIdentity': 'different', 'preview': {'operation': {}}}) as invoke:
            with self.assertRaisesRegex(ValueError, '대상 DB'):
                control.read_status({'maintenanceConfig': '/config', 'databaseIdentity': 'expected'}, '228000184')
            self.assertEqual(invoke.call_count, 1)
            self.assertEqual(invoke.call_args.args[1][0], 'routes-status')

    def test_not_ready_installation_never_invokes_maintenance(self):
        with patch.object(control.os, 'geteuid', return_value=0), \
                patch.object(control, 'parse'), \
                patch.object(control, 'private_root_file') as secure, \
                patch.object(control, 'execute') as execute:
            secure.return_value.read_text.return_value = '{"ready":false}'
            with self.assertRaisesRegex(ValueError, '배포/활성화'):
                control.main()
            execute.assert_not_called()

    def test_timeout_is_not_retried(self):
        with patch.object(control.subprocess, 'run', side_effect=control.subprocess.TimeoutExpired('java', 60)) as run:
            with self.assertRaises(control.subprocess.TimeoutExpired):
                control.invoke({'java': '/java', 'jar': '/jar'}, ['routes-service'])
            self.assertEqual(run.call_count, 1)


if __name__ == '__main__':
    unittest.main()
