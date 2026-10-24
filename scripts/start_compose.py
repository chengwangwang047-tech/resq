#!/usr/bin/env python3
"""Start Compose dependencies, verify P0 failure routing, then start ResQ.

This is a deployment action. It may recreate containers whose configuration changed.
It never reads the env file, expands configuration to stdout, or removes volumes.
"""
from pathlib import Path
import argparse
import os
import re
import subprocess
import sys

import configure_timeout_failure_policy as timeout_policy

PROJECT = Path(__file__).resolve().parents[1]


class DeploymentError(RuntimeError):
    pass


def compose_command(project, env_file, override=None):
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', project):
        raise ValueError('Invalid Compose project name')
    env_file = Path(env_file).resolve()
    if not env_file.is_file():
        raise ValueError('The explicitly selected local environment file does not exist')
    command = ['docker', 'compose', '--project-directory', str(PROJECT / 'deploy'),
               '--env-file', str(env_file), '-p', project,
               '-f', str(PROJECT / 'deploy/compose.yaml')]
    if override is not None:
        command += ['-f', str(Path(override).resolve())]
    return command


def invoke(command, env, label, timeout=300):
    try:
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise DeploymentError(label + ' could not complete (' + type(error).__name__ + ')') from None
    if result.returncode:
        if label == 'Configuration validation':
            missing = sorted(set(re.findall(r'required variable ([A-Z][A-Z0-9_]*)', result.stderr)))
            if missing:
                raise DeploymentError('Missing required settings: ' + ', '.join(missing))
        # Do not print arbitrary stderr: it may contain configuration or generated credentials.
        raise DeploymentError(label + ' failed (exit ' + str(result.returncode) + '); inspect Compose service status locally')
    return result.stdout.strip()


def start(command, env, build=False, wait_timeout=240):
    invoke(command + ['config', '--quiet'], env, 'Configuration validation')
    if build:
        print('Building the versioned application image.', flush=True)
        invoke(command + ['build', 'app'], env, 'Application image build', timeout=600)
    print('Starting and waiting for MySQL, Redis and RabbitMQ.', flush=True)
    invoke(command + ['up', '-d', '--wait', '--wait-timeout', str(wait_timeout),
                      'mysql', 'redis', 'rabbitmq'], env, 'Dependency startup', timeout=wait_timeout + 60)
    container = invoke(command + ['ps', '-q', 'rabbitmq'], env, 'Broker identity lookup')
    if not re.fullmatch(r'[a-f0-9]{64}', container):
        raise DeploymentError('Expected exactly one RabbitMQ container')
    # The reviewed script refuses conflicting policies and preserves queues/messages.
    timeout_policy.configure(container, apply=True)
    print('Failure routing verified; starting ResQ with the prod profile.', flush=True)
    invoke(command + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', str(wait_timeout), 'app'],
           env, 'Application startup', timeout=wait_timeout + 60)
    print('All four Compose services are ready.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, required=True)
    parser.add_argument('--project', default='deploy')
    parser.add_argument('--build', action='store_true', help='Build app first; requires the packaged target JAR')
    parser.add_argument('--wait-timeout', type=int, default=240)
    args = parser.parse_args()
    if args.wait_timeout < 1:
        parser.error('--wait-timeout must be positive')
    command = compose_command(args.project, args.env_file)
    start(command, dict(os.environ), args.build, args.wait_timeout)


if __name__ == '__main__':
    try:
        main()
    except (DeploymentError, ValueError) as error:
        print(str(error) + '. No volumes were removed.', file=sys.stderr)
        sys.exit(1)
    except (DeploymentError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print('Deployment stopped (' + type(error).__name__ + '). No volumes were removed.', file=sys.stderr)
        sys.exit(1)
