#!/usr/bin/env python3
import os
import unittest
from unittest.mock import patch, MagicMock
import run_integration_tests as runner


class IntegrationRunnerSafetyTest(unittest.TestCase):
    def test_inherited_application_credentials_and_jvm_overrides_are_removed(self):
        source = {'PATH': '/synthetic/path', 'RESQ_PROD_DB_PASSWORD': 'test-placeholder',
                  'SPRING_PROFILES_ACTIVE': 'prod', 'MYSQL_PWD': 'test-placeholder',
                  'REDIS_PASSWORD': 'test-placeholder', 'RABBITMQ_PASSWORD': 'test-placeholder',
                  'JAVA_TOOL_OPTIONS': '-Dsynthetic=true', 'MAVEN_ARGS': '-Dsynthetic=true'}
        with patch.dict(os.environ, source, clear=True):
            self.assertEqual({'PATH': '/synthetic/path'}, runner.clean_environment())

    def test_occupied_dedicated_port_refuses_before_any_docker_operation(self):
        sock = MagicMock()
        sock.__enter__.return_value.bind.side_effect = OSError('synthetic port conflict')
        with patch.object(runner.sys, 'platform', 'linux'), patch.object(runner.socket, 'socket', return_value=sock), patch.object(runner, 'run') as command:
            with self.assertRaisesRegex(RuntimeError, 'port is occupied'):
                runner.main()
            command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
