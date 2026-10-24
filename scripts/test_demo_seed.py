#!/usr/bin/env python3
"""Offline tests for demo seed ownership boundaries; never invokes Docker."""
import json
import unittest
from unittest.mock import patch
import seed_demo_data as seed

ID='1'*64
GUARD='a'*32
PROJECT='resq-demo-'+GUARD[:12]
IDENTITY=PROJECT+'|mysql|'+GUARD+'|true|mysql:8.0.46'


class DemoSeedSafetyTest(unittest.TestCase):
    def test_name_or_missing_guard_rejected_before_docker(self):
        for identity,guard in [('deploy-mysql-1',GUARD),(ID,'')]:
            with self.subTest(identity=identity),patch.object(seed,'command') as command:
                with self.assertRaises(RuntimeError):seed.seed(identity,guard)
                command.assert_not_called()

    def test_original_deploy_project_is_rejected_before_database_access(self):
        with patch.object(seed,'inspect',return_value='deploy|mysql|'+GUARD+'|true|mysql:8.0.46'),patch.object(seed,'execute_sql') as sql:
            with self.assertRaises(RuntimeError):seed.seed(ID,GUARD)
            sql.assert_not_called()

    def test_development_data_volume_is_rejected_even_with_demo_container_labels(self):
        mounts=[dict(Destination='/var/lib/mysql',Type='volume',Name='deploy_mysql_data')]
        with patch.object(seed,'inspect',side_effect=[IDENTITY,'{}',json.dumps(mounts)]),patch.object(seed,'execute_sql') as sql:
            with self.assertRaises(RuntimeError):seed.seed(ID,GUARD)
            sql.assert_not_called()

    def test_named_volume_requires_matching_project_and_guard(self):
        mounts=[dict(Destination='/var/lib/mysql',Type='volume',Name=PROJECT+'_mysql_data')]
        with patch.object(seed,'inspect',side_effect=[IDENTITY,'{}',json.dumps(mounts)]),patch.object(seed,'command',return_value=PROJECT+'|wrong-guard'),patch.object(seed,'execute_sql') as sql:
            with self.assertRaises(RuntimeError):seed.seed(ID,GUARD)
            sql.assert_not_called()

    def test_running_demo_application_prevents_seeding(self):
        with patch.object(seed,'inspect',side_effect=[IDENTITY,json.dumps({'/var/lib/mysql':'rw'}),'[]']),patch.object(seed,'command',return_value='2'*64),patch.object(seed,'execute_sql') as sql:
            with self.assertRaisesRegex(RuntimeError,'Stop the demo application'):seed.seed(ID,GUARD)
            sql.assert_not_called()

    def test_own_tmpfs_database_passes_ownership_check(self):
        with patch.object(seed,'inspect',side_effect=[IDENTITY,json.dumps({'/var/lib/mysql':'rw'}),'[]']),patch.object(seed,'command',return_value=''):
            self.assertEqual(PROJECT,seed.verify_target(ID,GUARD,{}))

    def test_own_named_volume_passes_ownership_check(self):
        mounts=[dict(Destination='/var/lib/mysql',Type='volume',Name=PROJECT+'_mysql_data')]
        with patch.object(seed,'inspect',side_effect=[IDENTITY,'{}',json.dumps(mounts)]),patch.object(seed,'command',side_effect=[PROJECT+'|'+GUARD,'']):
            self.assertEqual(PROJECT,seed.verify_target(ID,GUARD,{}))

    def test_command_error_does_not_disclose_stderr(self):
        from subprocess import CompletedProcess
        result=CompletedProcess(['synthetic-command'],1,'','SYNTHETIC-PRIVATE-VALUE')
        with patch.object(seed.subprocess,'run',return_value=result):
            with self.assertRaises(RuntimeError) as error:seed.command(['synthetic-command'],{})
            self.assertNotIn('SYNTHETIC-PRIVATE-VALUE',str(error.exception))


if __name__=='__main__':unittest.main()
