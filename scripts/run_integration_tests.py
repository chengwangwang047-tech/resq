#!/usr/bin/env python3
"""Run ResQ integration tests against newly created disposable Docker instances only."""
from pathlib import Path
import base64
import datetime
import json
import os
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
PORTS = (13317, 16317, 56719, 15679)


def clean_environment():
    ignored = {'MYSQL_PWD', 'REDISCLI_AUTH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
               '_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS'}
    return {key: value for key, value in os.environ.items()
            if not key.startswith(('RESQ_', 'SPRING_', 'MYSQL_', 'REDIS_', 'RABBITMQ_')) and key not in ignored}


def run(args, *, env, data=None, timeout=30):
    return subprocess.run(args, input=data, env=env, text=True, capture_output=True,
                          check=True, timeout=timeout).stdout.strip()


def run_standard_tests():
    if sys.platform != 'linux':
        raise RuntimeError('Run inside WSL Ubuntu')
    env = clean_environment()
    env['MAVEN_SKIP_RC'] = 'true'
    # Refuse conflicts; never stop any pre-existing process or reuse its database.
    for port in PORTS:
        with socket.socket() as sock:
            try:
                sock.bind(('127.0.0.1', port))
            except OSError:
                raise RuntimeError('Dedicated integration port is occupied: ' + str(port))
    guard = secrets.token_hex(16)
    suffix = guard[:10]
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    results = PROJECT / 'target/integration-results' / (stamp + '-' + suffix)
    results.mkdir(parents=True)
    child = dict(env)
    child.update({'MYSQL_ROOT_PASSWORD': secrets.token_urlsafe(24),
                  'MYSQL_DATABASE': 'resq_day17', 'MYSQL_USER': 'day17_test',
                  'MYSQL_PASSWORD': secrets.token_urlsafe(24),
                  'RABBITMQ_DEFAULT_USER': 'resq_it_' + suffix,
                  'RABBITMQ_DEFAULT_PASS': secrets.token_urlsafe(24),
                  'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS': '+S 2:2',
                  'RABBITMQ_CTL_ERL_ARGS': '+S 1:1'})
    owned = []
    statuses = []
    mysql = redis = rabbit = None
    try:
        specs = [
            ('mysql', 'mysql:8.0.46', ['--tmpfs', '/var/lib/mysql:rw', '--memory', '1g',
              '-p', '127.0.0.1:13317:13317', '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_DATABASE',
              '-e', 'MYSQL_USER', '-e', 'MYSQL_PASSWORD'],
             ['--port=13317', '--innodb-buffer-pool-size=64M', '--max-connections=64', '--default-time-zone=+00:00']),
            ('redis', 'redis:7-alpine', ['--tmpfs', '/data:rw', '-p', '127.0.0.1:16317:16317'],
             ['redis-server', '--port', '16317', '--save', '', '--appendonly', 'no']),
            ('rabbitmq', 'rabbitmq:3.13-management', ['--tmpfs', '/var/lib/rabbitmq:rw',
             '-p', '127.0.0.1:56719:5672', '-p', '127.0.0.1:15679:15672',
             '-e', 'RABBITMQ_DEFAULT_USER', '-e', 'RABBITMQ_DEFAULT_PASS',
             '-e', 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS', '-e', 'RABBITMQ_CTL_ERL_ARGS'], []),
        ]
        ids = {}
        for service, image, options, command in specs:
            # Images must already exist: do not pull/update user's runtime as part of testing.
            run(['docker', 'image', 'inspect', '--format', '{{.Id}}', image], env=env)
            container = run(['docker', 'create', '--name', 'resq-it-' + service + '-' + suffix,
                             '--label', 'resq.integration.guard=' + guard, *options, image, *command], env=child)
            owned.append(container)
            ids[service] = container
            run(['docker', 'start', container], env=env)
        mysql, redis, rabbit = (ids[name] for name in ('mysql', 'redis', 'rabbitmq'))
        print('Created three isolated containers with private memory-backed data; results:', results, flush=True)
        mysql_env = dict(env, MYSQL_PWD=child['MYSQL_PASSWORD'])
        mysql_cli = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', mysql, 'mysql',
                     '-h127.0.0.1', '-P13317', '-uday17_test', '--database', 'resq_day17']

        def ready(command, command_env):
            try:
                run(command, env=command_env, timeout=8)
                return True
            except (subprocess.SubprocessError, OSError):
                return False

        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if (ready(mysql_cli + ['--execute', 'SELECT 1'], mysql_env)
                    and ready(['docker', 'exec', redis, 'redis-cli', '-p', '16317', 'PING'], env)
                    and ready(['docker', 'exec', '--user', 'rabbitmq', rabbit,
                               'rabbitmq-diagnostics', '-q', '-t', '5', 'check_running'], env)):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Disposable dependencies did not become ready')
        schema = (PROJECT / 'deploy/init/01-schema.sql').read_text()
        schema += "\nCREATE TABLE day17_safety_guard(token CHAR(32) NOT NULL);\n"
        schema += "INSERT INTO day17_safety_guard VALUES ('" + guard + "');\n"
        run(mysql_cli, env=mysql_env, data=schema)
        run(['docker', 'exec', redis, 'redis-cli', '-p', '16317', 'SET', 'day17:safety-guard', guard], env=env)
        credentials = base64.b64encode((child['RABBITMQ_DEFAULT_USER'] + ':' + child['RABBITMQ_DEFAULT_PASS']).encode()).decode()
        req = urllib.request.Request('http://127.0.0.1:15679/api/queues/%2F/resq-it-guard-' + guard,
                data=json.dumps({'durable': True, 'auto_delete': False, 'arguments': {}}).encode(), method='PUT',
                headers={'Authorization': 'Basic ' + credentials, 'Content-Type': 'application/json'})
        for attempt in range(20):
            try:
                with urllib.request.urlopen(req, timeout=3):
                    break
            except OSError:
                if attempt == 19:
                    raise RuntimeError('Isolated management API did not become ready')
                time.sleep(1)
        test_env = dict(env)
        test_env.update({'RESQ_IT_GUARD': guard, 'RESQ_IT_DB_PASSWORD': child['MYSQL_PASSWORD'],
                         'RESQ_IT_RABBIT_USER': child['RABBITMQ_DEFAULT_USER'],
                         'RESQ_IT_RABBIT_PASSWORD': child['RABBITMQ_DEFAULT_PASS'],
                         'RESQ_IT_JWT_SECRET_BASE64': base64.b64encode(secrets.token_bytes(32)).decode(),
                         'JAVA_TOOL_OPTIONS': '-Duser.timezone=UTC'})
        reports = results / 'surefire-reports'
        command = ['mvn', '-o', '-B', '-Pintegration', 'test',
                   '-Dtest=**/*Test',
                   '-Dresq.day17.integration=true',
                   '-Dday17.mysql.url=jdbc:mysql://127.0.0.1:13317/resq_day17?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&connectTimeout=2000&socketTimeout=10000',
                   '-Dday17.redis.port=16317', '-Dday17.guard=' + guard,
                   '-Dday17.results=' + str(results), '-Dtest.reportsDirectory=' + str(reports)]
        with (results / 'maven.log').open('w') as log:
            process = subprocess.Popen(command, cwd=PROJECT, env=test_env, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            try:
                process.wait(timeout=480)
            finally:
                # Maven may have forked a JVM; terminate only this invocation's process group.
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
        for path in sorted(reports.glob('TEST-*.xml')):
            suite = ET.parse(path).getroot()
            statuses.append({key: suite.attrib.get(key) for key in ('name', 'tests', 'failures', 'errors', 'skipped')})
        (results / 'summary.json').write_text(json.dumps(statuses, indent=2))
        for status in statuses:
            print('RESULT', json.dumps(status), flush=True)
        if process.returncode:
            raise RuntimeError('Integration tests failed; inspect the isolated Maven report')
        if not statuses:
            raise RuntimeError('No integration reports were produced')
        if any(int(s[key]) for s in statuses for key in ('failures', 'errors', 'skipped')):
            raise RuntimeError('Standard integration tests must pass without skips')
        print('STANDARD_INTEGRATION_SUITE_PASSED', flush=True)
        return results
    finally:
        cleanup = []
        for container in reversed(owned):
            value = run(['docker', 'inspect', '--format', '{{index .Config.Labels "resq.integration.guard"}}', container], env=env)
            if value != guard:
                raise RuntimeError('Refusing cleanup of a container with mismatched ownership')
            run(['docker', 'rm', '-f', container], env=env)
            cleanup.append(container)
        (results / 'cleanup.json').write_text(json.dumps({'removedOwnedContainerIds': cleanup, 'existingVolumesTouched': False}, indent=2))
        print('Removed only this run\'s three disposable containers; existing services/volumes preserved.', flush=True)


def main():
    # The first phase cleans up its three containers before the dedicated phase starts.
    # Its Maven selection excludes *IT; those suites run once in their own instances below.
    results = run_standard_tests()
    statuses = json.loads((results / 'summary.json').read_text())
    outcome = {'passed': False, 'standardSuitesPassed': True, 'dedicatedSuitesPassed': False}
    try:
        from run_p0_integration_tests import main as run_dedicated_tests
        statuses.extend(run_dedicated_tests(results / 'p0'))
        (results / 'summary.json').write_text(json.dumps(statuses, indent=2) + '\n')
        outcome.update(passed=True, dedicatedSuitesPassed=True,
                       totals={key: sum(int(s[key]) for s in statuses)
                               for key in ('tests', 'failures', 'errors', 'skipped')})
        print('INTEGRATION_SUITE_PASSED', json.dumps(outcome['totals']), flush=True)
        return 0
    finally:
        (results / 'acceptance.json').write_text(json.dumps(outcome, indent=2) + '\n')


if __name__ == '__main__':
    os.umask(0o077)
    try:
        sys.exit(main())
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError, ET.ParseError) as exc:
        # Exception text from commands could contain generated credentials; do not dump it.
        print('Isolated integration run failed (' + type(exc).__name__ + '); inspect target/integration-results.', file=sys.stderr)
        sys.exit(1)
