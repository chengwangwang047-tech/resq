#!/usr/bin/env python3
"""Offline tests for the boundaries that protect existing deployment data."""
from pathlib import Path
import copy
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import start_compose as deployment
import verify_compose_deployment as acceptance

GUARD = 'a' * 32
PROJECT = 'resq-compose-it-' + GUARD[:12]


class ComposeSafetyTest(unittest.TestCase):
    def test_acceptance_cannot_target_live_project(self):
        for name in ('deploy', 'resq', 'resq-compose-it-' + 'b' * 12):
            with self.assertRaises(RuntimeError):
                acceptance.assert_isolated_project(name, GUARD)

    def test_ownership_mismatch_refuses_cleanup_before_down(self):
        owned = {'container': ['synthetic-id'], 'network': [], 'volume': []}
        with patch.object(acceptance, 'resources', return_value=owned), \
             patch.object(acceptance, 'verify_resource', side_effect=RuntimeError('mismatch')), \
             patch.object(acceptance, 'run') as command:
            with self.assertRaises(RuntimeError):
                acceptance.cleanup(['docker', 'compose'], {}, PROJECT, GUARD)
            command.assert_not_called()

    def test_guard_must_match_for_every_resource_kind(self):
        for kind in ('container', 'network', 'volume'):
            with self.assertRaises(RuntimeError):
                acceptance.assert_ownership(kind, PROJECT, GUARD, PROJECT, 'b' * 32)

    def test_external_or_live_data_volume_is_rejected(self):
        model = {'name': PROJECT, 'services': {key: {} for key in ('app', 'mysql', 'redis', 'rabbitmq')},
                 'volumes': {key: {'name': PROJECT + '_' + key} for key in ('mysql_data', 'redis_data', 'rabbitmq_data')},
                 'networks': {'resq_net': {'name': PROJECT + '_resq_net'}}}
        for override in ({'name': 'deploy_mysql_data'}, {'external': True}):
            changed = copy.deepcopy(model)
            changed['volumes']['mysql_data'].update(override)
            with self.assertRaises(RuntimeError):
                acceptance.validate_model(changed, PROJECT)

    def test_inherited_compose_and_live_application_settings_are_removed(self):
        values = {'PATH': '/synthetic', 'COMPOSE_PROJECT_NAME': 'deploy', 'COMPOSE_FILE': '/synthetic/live',
                  'RESQ_APP_IMAGE': 'synthetic', 'SPRING_PROFILES_ACTIVE': 'dev',
                  'MYSQL_PWD': 'synthetic', 'JAVA_TOOL_OPTIONS': '-Dsynthetic=true'}
        with patch.dict(os.environ, values, clear=True):
            self.assertEqual({'PATH': '/synthetic'}, acceptance.clean_environment())

    def test_missing_variable_diagnostic_does_not_echo_other_stderr(self):
        result = subprocess.CompletedProcess([], 1, '',
            'required variable RESQ_PROD_JWT_SECRET_BASE64 is missing a value; synthetic-private-value')
        with patch.object(deployment.subprocess, 'run', return_value=result):
            with self.assertRaises(deployment.DeploymentError) as error:
                deployment.invoke(['synthetic'], {}, 'Configuration validation')
        self.assertIn('RESQ_PROD_JWT_SECRET_BASE64', str(error.exception))
        self.assertNotIn('synthetic-private-value', str(error.exception))

    def test_missing_explicit_env_file_refuses_before_docker(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(deployment.subprocess, 'run') as command:
            with self.assertRaises(ValueError):
                deployment.compose_command('deploy', Path(directory) / 'missing.env')
            command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
