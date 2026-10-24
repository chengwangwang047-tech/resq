#!/usr/bin/env python3
"""Offline safety tests; these tests never invoke Docker or Maven."""
from pathlib import Path
import json
import os
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import run_p0_integration_tests as runner


class DedicatedRunnerSafetyTest(unittest.TestCase):
    def test_inherited_p0_credentials_and_jvm_options_do_not_enable_tests(self):
        source = {'PATH': '/synthetic', 'RESQ_P0_ISOLATED_MYSQL': 'a'*32,
                  'RESQ_P0_MYSQL_PASSWORD': 'synthetic', 'RESQ_P0_RABBIT_PASSWORD': 'synthetic',
                  'SPRING_PROFILES_ACTIVE': 'prod', 'MYSQL_PWD': 'synthetic',
                  'MAVEN_OPTS': '-Dsynthetic=true', 'JAVA_TOOL_OPTIONS': '-Dsynthetic=true'}
        with patch.dict(os.environ, source, clear=True):
            self.assertEqual({'PATH': '/synthetic'}, runner.clean_environment())

    def test_container_names_are_not_accepted_as_owned_ids(self):
        with patch.object(runner, 'run') as command:
            with self.assertRaisesRegex(RuntimeError, 'exact'):
                runner.require_owned('deploy-mysql-1', 'a'*32, {})
            command.assert_not_called()

    def test_cleanup_checks_every_owner_before_any_removal(self):
        first, second, guard = '1'*64, '2'*64, 'a'*32
        with patch.object(runner, 'run', side_effect=[guard, 'different-guard']) as command:
            with self.assertRaisesRegex(RuntimeError, 'ownership mismatch'):
                runner.cleanup_owned([first, second], guard, {})
            self.assertEqual(2, command.call_count)
            self.assertTrue(all(c.args[0][1]=='inspect' for c in command.call_args_list))

    def test_cleanup_removes_only_exact_owned_ids(self):
        first, second, guard = '1'*64, '2'*64, 'a'*32
        with patch.object(runner, 'run', side_effect=[guard,guard,'','']) as command:
            self.assertEqual([second,first],runner.cleanup_owned([first,second],guard,{}))
            removals=[c.args[0] for c in command.call_args_list if c.args[0][1]=='rm']
            self.assertEqual([['docker','rm','-f',second],['docker','rm','-f',first]],removals)

    def test_public_bindings_and_development_ports_are_rejected(self):
        for records in ([{'HostIp':'0.0.0.0','HostPort':'49001'}],
                        [{'HostIp':'127.0.0.1','HostPort':'3306'}],
                        [{'HostIp':'127.0.0.1','HostPort':'5672'}], [],
                        [{'HostIp':'127.0.0.1','HostPort':'49001'}]*2):
            with self.subTest(records=records), patch.object(runner,'run',return_value=json.dumps({'3306/tcp':records})):
                with self.assertRaises(RuntimeError):
                    runner.published_port('1'*64,3306,{})

    def test_single_dynamic_loopback_port_is_accepted(self):
        value=json.dumps({'3306/tcp':[{'HostIp':'127.0.0.1','HostPort':'49001'}]})
        with patch.object(runner,'run',return_value=value):
            self.assertEqual(49001,runner.published_port('1'*64,3306,{}))

    def reports(self, folder, *, skip=0):
        for name,count in runner.EXPECTED.items():
            (folder/('TEST-'+name+'.xml')).write_text(
                f'<testsuite name="{name}" tests="{count}" failures="0" errors="0" skipped="{skip}"/>')

    def test_both_suites_must_really_run_all_eight_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); self.reports(folder)
            self.assertEqual(8,sum(int(s['tests']) for s in runner.collect_reports(folder)))

    def test_skipped_dedicated_tests_fail_acceptance(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); self.reports(folder,skip=1)
            with self.assertRaisesRegex(RuntimeError,'without skips'):
                runner.collect_reports(folder)

    def test_missing_dedicated_suite_fails_acceptance(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); self.reports(folder)
            next(folder.glob('TEST-*.xml')).unlink()
            with self.assertRaisesRegex(RuntimeError,'Both dedicated'):
                runner.collect_reports(folder)

    def test_maven_timeout_terminates_only_its_own_process_group(self):
        process=MagicMock(pid=43210)
        process.wait.side_effect=subprocess.TimeoutExpired('synthetic-maven',240)
        process.poll.return_value=-15
        with tempfile.TemporaryDirectory() as temporary, patch.object(runner.subprocess,'Popen',return_value=process) as start, patch.object(runner.os,'killpg') as kill:
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.execute_maven(Path(temporary),{})
            self.assertTrue(start.call_args.kwargs['start_new_session'])
            kill.assert_called_once_with(43210,signal.SIGTERM)

    def test_unavailable_image_never_creates_or_removes_containers(self):
        with patch.object(runner.sys,'platform','linux'), patch.object(runner,'run',side_effect=OSError('synthetic missing image')) as command:
            with self.assertRaises(OSError):
                runner.main()
            self.assertEqual(['docker','image','inspect'],command.call_args.args[0][:3])
            self.assertEqual(1,command.call_count)

    def test_full_entry_propagates_dedicated_failure(self):
        import run_integration_tests as full
        with tempfile.TemporaryDirectory() as temporary:
            results=Path(temporary)
            (results/'summary.json').write_text('[]')
            with patch.object(full,'run_standard_tests',return_value=results), patch.object(runner,'main',side_effect=RuntimeError('synthetic failed P0 phase')):
                with self.assertRaises(RuntimeError): full.main()
            self.assertFalse(json.loads((results/'acceptance.json').read_text())['passed'])

    def test_full_entry_combines_standard_and_dedicated_results(self):
        import run_integration_tests as full
        with tempfile.TemporaryDirectory() as temporary:
            results=Path(temporary)
            standard=[dict(name='Standard',tests='29',failures='0',errors='0',skipped='0')]
            dedicated=[dict(name=n,tests=str(c),failures='0',errors='0',skipped='0') for n,c in runner.EXPECTED.items()]
            (results/'summary.json').write_text(json.dumps(standard))
            with patch.object(full,'run_standard_tests',return_value=results), patch.object(runner,'main',return_value=dedicated):
                self.assertEqual(0,full.main())
            acceptance=json.loads((results/'acceptance.json').read_text())
            self.assertTrue(acceptance['passed'])
            self.assertEqual({'tests':37,'failures':0,'errors':0,'skipped':0},acceptance['totals'])


if __name__ == '__main__':
    unittest.main()
