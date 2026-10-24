#!/usr/bin/env python3
"""Run the eight opt-in P0 tests using two new disposable Docker containers only."""
from pathlib import Path
import datetime
import json
import os
import re
import secrets
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from run_integration_tests import clean_environment, run

PROJECT = Path(__file__).resolve().parents[1]
LABEL = 'resq.p0.guard'
EXPECTED = {
    'com.resq.service.ReservationTimeBoundaryIT': 7,
    'com.resq.mq.TimeoutFailureRoutingIT': 1,
}


def require_owned(container, guard, env):
    if not re.fullmatch(r'[a-f0-9]{64}', container):
        raise RuntimeError('An exact newly created container ID is required')
    actual = run(['docker', 'inspect', '--format', '{{index .Config.Labels "' + LABEL + '"}}', container], env=env)
    if actual != guard:
        raise RuntimeError('Refusing operation: container ownership mismatch')


def published_port(container, internal, env):
    bindings = json.loads(run(['docker', 'inspect', '--format',
                              '{{json .NetworkSettings.Ports}}', container], env=env))
    records = bindings.get(str(internal) + '/tcp', [])
    if len(records) != 1 or records[0]['HostIp'] != '127.0.0.1':
        raise RuntimeError('A single loopback-only test port is required')
    port = int(records[0]['HostPort'])
    if not 1024 < port <= 65535 or port in (3306, 5672):
        raise RuntimeError('Refusing a development service port')
    return port


def collect_reports(directory):
    suites = []
    for path in sorted(directory.glob('TEST-*.xml')):
        attrs = ET.parse(path).getroot().attrib
        suites.append({key: attrs[key] for key in ('name', 'tests', 'failures', 'errors', 'skipped')})
    if len(suites) != len(EXPECTED) or {s['name'] for s in suites} != set(EXPECTED):
        raise RuntimeError('Both dedicated test suites must produce fresh reports')
    for suite in suites:
        if (int(suite['tests']) != EXPECTED[suite['name']]
                or any(int(suite[key]) != 0 for key in ('failures', 'errors', 'skipped'))):
            raise RuntimeError('All eight dedicated tests must execute and pass without skips')
    return suites


def execute_maven(results, env):
    command = ['mvn', '-o', '-B', '-Pintegration', 'test',
               '-Dtest=ReservationTimeBoundaryIT,TimeoutFailureRoutingIT',
               '-Dtest.reportsDirectory=' + str(results / 'surefire-reports')]
    with (results / 'maven.log').open('w') as log:
        process = subprocess.Popen(command, cwd=PROJECT, env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            process.wait(timeout=240)
        finally:
            # Scope termination to this Maven process group, including a forked JVM.
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            if process.poll() is None:
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
    if process.returncode:
        raise RuntimeError('Dedicated Maven tests failed; inspect the isolated report')


def cleanup_owned(owned, guard, env):
    # Validate every target before removing any, so a mismatch fails closed.
    for container in owned:
        require_owned(container, guard, env)
    removed = []
    for container in reversed(owned):
        run(['docker', 'rm', '-f', container], env=env)
        removed.append(container)
    return removed


def main(results=None):
    if sys.platform != 'linux':
        raise RuntimeError('Run inside WSL Ubuntu')
    env = clean_environment()
    env['MAVEN_SKIP_RC'] = 'true'
    # No pull, no reuse of existing containers, no project .env or configuration loading.
    for image in ('mysql:8.0.46', 'rabbitmq:3.13-management'):
        run(['docker', 'image', 'inspect', '--format', '{{.Id}}', image], env=env)
    guard = secrets.token_hex(16)
    suffix = guard[:10]
    if results is None:
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        results = PROJECT / 'target/p0-integration-results' / (stamp + '-' + suffix)
    results = Path(results)
    results.mkdir(parents=True, mode=0o700, exist_ok=False)
    child = dict(env, MYSQL_ROOT_PASSWORD=secrets.token_urlsafe(24), MYSQL_DATABASE='resq_p0',
                 MYSQL_USER='p0_test', MYSQL_PASSWORD=secrets.token_urlsafe(24),
                 RABBITMQ_DEFAULT_USER='resq_p0_' + suffix,
                 RABBITMQ_DEFAULT_PASS=secrets.token_urlsafe(24),
                 RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='+S 2:2', RABBITMQ_CTL_ERL_ARGS='+S 1:1')
    owned = []
    outcome = {'passed': False, 'suites': [], 'existingVolumesTouched': False}
    try:
        specs = [
            ('mysql', 'mysql:8.0.46', ['--tmpfs', '/var/lib/mysql:rw', '--memory', '1g',
             '-p', '127.0.0.1::3306', '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_DATABASE',
             '-e', 'MYSQL_USER', '-e', 'MYSQL_PASSWORD'],
             ['--innodb-buffer-pool-size=64M', '--max-connections=30', '--default-time-zone=+00:00']),
            ('rabbitmq', 'rabbitmq:3.13-management', ['--tmpfs', '/var/lib/rabbitmq:rw',
             '-p', '127.0.0.1::5672', '-e', 'RABBITMQ_DEFAULT_USER', '-e', 'RABBITMQ_DEFAULT_PASS',
             '-e', 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS', '-e', 'RABBITMQ_CTL_ERL_ARGS'], []),
        ]
        ids = {}
        for service, image, options, command in specs:
            container = run(['docker', 'create', '--name', 'resq-p0-it-' + service + '-' + suffix,
                             '--label', LABEL + '=' + guard, *options, image, *command], env=child)
            if not re.fullmatch(r'[a-f0-9]{64}', container):
                raise RuntimeError('Docker did not return an exact container ID')
            owned.append(container)
            require_owned(container, guard, env)
            ids[service] = container
            run(['docker', 'start', container], env=env)
        mysql, rabbit = ids['mysql'], ids['rabbitmq']
        mysql_env = dict(env, MYSQL_PWD=child['MYSQL_PASSWORD'])
        mysql_cli = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', mysql, 'mysql',
                     '-h127.0.0.1', '-up0_test', '--database', 'resq_p0']

        def ready(command, command_env):
            try:
                run(command, env=command_env, timeout=12)
                return True
            except (subprocess.SubprocessError, OSError):
                return False

        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if (ready(mysql_cli + ['--execute', 'SELECT 1'], mysql_env)
                    and ready(['docker', 'exec', '--user', 'rabbitmq', rabbit,
                               'rabbitmq-diagnostics', '-q', '-t', '5', 'check_running'], env)
                    and ready(['docker', 'exec', '--user', 'rabbitmq', rabbit,
                               'rabbitmq-diagnostics', '-q', '-t', '5', 'check_port_connectivity'], env)):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Dedicated disposable dependencies did not become ready')
        require_owned(mysql, guard, env)
        schema = (PROJECT / 'deploy/init/01-schema.sql').read_text()
        schema += '\nCREATE TABLE p0_safety_guard (guard CHAR(32) NOT NULL);\n'
        schema += "INSERT INTO p0_safety_guard VALUES ('" + guard + "');\n"
        run(mysql_cli, env=mysql_env, data=schema)
        require_owned(rabbit, guard, env)
        run([sys.executable, str(PROJECT / 'scripts/configure_timeout_failure_policy.py'),
             '--container', rabbit, '--apply'], env=env)
        test_env = dict(env, RESQ_P0_ISOLATED_MYSQL=guard,
                        RESQ_P0_MYSQL_PORT=str(published_port(mysql, 3306, env)),
                        RESQ_P0_MYSQL_PASSWORD=child['MYSQL_PASSWORD'],
                        RESQ_P0_ISOLATED_BROKER=guard,
                        RESQ_P0_RABBIT_PORT=str(published_port(rabbit, 5672, env)),
                        RESQ_P0_RABBIT_USER=child['RABBITMQ_DEFAULT_USER'],
                        RESQ_P0_RABBIT_PASSWORD=child['RABBITMQ_DEFAULT_PASS'],
                        JAVA_TOOL_OPTIONS='-Duser.timezone=UTC')
        print('Running eight dedicated tests in two new isolated containers; results:', results, flush=True)
        execute_maven(results, test_env)
        outcome['suites'] = collect_reports(results / 'surefire-reports')
        outcome['passed'] = True
        for suite in outcome['suites']:
            print('RESULT', json.dumps(suite), flush=True)
        return outcome['suites']
    finally:
        cleanup = {'removedOwnedContainerIds': [], 'existingVolumesTouched': False, 'ownershipVerified': False}
        try:
            cleanup['removedOwnedContainerIds'] = cleanup_owned(owned, guard, env)
            cleanup['ownershipVerified'] = True
        except BaseException:
            outcome['passed'] = False
            outcome['cleanupFailed'] = True
            raise
        finally:
            (results / 'summary.json').write_text(json.dumps(outcome, indent=2) + '\n')
            (results / 'cleanup.json').write_text(json.dumps(cleanup, indent=2) + '\n')
        print('Removed only this run\'s two dedicated containers; existing services/volumes preserved.', flush=True)


if __name__ == '__main__':
    # Logs may contain synthetic test parameters; keep all result files local and private.
    os.umask(0o077)
    try:
        main()
        print('P0_INTEGRATION_SUITE_PASSED', flush=True)
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError, ET.ParseError) as exc:
        print('Dedicated integration run failed (' + type(exc).__name__ +
              '); inspect target/p0-integration-results.', file=sys.stderr)
        sys.exit(1)
