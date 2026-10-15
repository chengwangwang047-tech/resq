#!/usr/bin/env python3
"""Run Day18 regression groups and the complete suite on owned disposable services.

Usage: python3 scripts/day18/run_tests.py [--include-day17-integration]
The default runs the relevant groups, then an actual `mvn test` without -Dtest.
No live application configuration, ~/.resq-env, credentials or database is used.
"""
from pathlib import Path
import argparse
import base64
import datetime
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[2]
STAMP = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
RESULTS = PROJECT / 'target/day18-results' / (STAMP + '-' + uuid.uuid4().hex[:8])
GROUPS = [
    ('contracts-and-units', ','.join([
        'ResourceSlotsTest', 'Day17ReservationHttpContractTest',
        'ReservationLockServiceTest', 'ReservationServiceTest',
        'ResourceSlotCacheInvalidatorTest', 'ResourceSlotCacheRetryTest',
        'ResourceSlotCacheTest',
    ])),
    ('cache-http', 'Day15CacheHttpTest,ResourceSlotsRedisFailureHttpTest'),
]
DAY17 = 'Day17LockTransactionTest,Day17ConcurrencyComparisonTest'
MYSQL_URL = ('jdbc:mysql://127.0.0.1:13317/resq_day17?characterEncoding=UTF-8'
             '&connectionTimeZone=Asia/Shanghai&connectTimeout=2000&socketTimeout=10000')

# Reuse the reviewed ownership, port, hashing, startup and cleanup guards.
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('day17_private_runner', PROJECT / 'scripts/day17/run_tests.py')
day17 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(day17)
day17.RESULTS = RESULTS


def environment():
    result = day17.private_environment()
    # Maven/JVM options can inject properties before this runner's safe arguments.
    for key in ('JAVA_OPTS', 'JAVA_OPTIONS', 'MAVEN_CONFIG', 'MAVEN_PROJECTBASEDIR',
                'MAVEN_CMD_LINE_ARGS', 'SPRING_APPLICATION_JSON'):
        result.pop(key, None)
    # The Maven launcher normally sources ~/.mavenrc; it must not reload live settings.
    result['MAVEN_SKIP_RC'] = 'true'
    return result


def start_services(temp, instances, handles, guard):
    data = temp / 'mysql-data'
    data.mkdir()

    def log(name):
        handle = (RESULTS / name).open('w')
        handles.append(handle)
        return handle

    day17.run(['mysqld', '--no-defaults', '--initialize-insecure', f'--datadir={data}',
               '--innodb-buffer-pool-size=64M'], log('mysql-init.log'), timeout=60)
    mysql = subprocess.Popen([
        'mysqld', '--no-defaults', f'--datadir={data}',
        f'--socket={temp / "mysql.sock"}', f'--pid-file={temp / "mysql.pid"}',
        '--port=13317', '--bind-address=127.0.0.1', '--mysqlx=OFF',
        '--skip-log-bin', '--innodb-buffer-pool-size=64M', '--max-connections=64',
    ], stdout=log('mysql.log'), stderr=subprocess.STDOUT,
       start_new_session=True, env=environment())
    instances.append(('mysql', mysql))
    cli = ['mysql', '--no-defaults', '--protocol=SOCKET', f'--socket={temp / "mysql.sock"}',
           '-u', 'root', '--batch', '--skip-column-names']
    day17.wait_ready(lambda: subprocess.run(
        cli + ['-e', 'SELECT 1'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        timeout=2, env=environment()).returncode == 0, mysql)
    # The checked-in disposable schema covers every current mapper column and has no FK.
    # Mapper tests using user/slot=1 therefore need no persistent fixture; their writes roll back.
    sql = (PROJECT / 'src/test/resources/day17-schema.sql').read_text()
    sql = 'CREATE DATABASE resq_day17 CHARACTER SET utf8mb4;\nUSE resq_day17;\n' + sql
    sql += '\nCREATE TABLE day17_safety_guard(token CHAR(32) NOT NULL);\n'
    sql += f"INSERT INTO day17_safety_guard VALUES('{guard}');\n"
    for host in ('127.0.0.1', 'localhost'):
        sql += f"CREATE USER 'day17_test'@'{host}' IDENTIFIED BY '';\n"
        sql += f"GRANT ALL ON resq_day17.* TO 'day17_test'@'{host}';\n"
    day17.run(cli, data=sql)
    redis = subprocess.Popen([
        'redis-server', '--bind', '127.0.0.1', '--port', '16317', '--save', '',
        '--appendonly', 'no', '--dir', str(temp), '--daemonize', 'no',
    ], stdout=log('redis.log'), stderr=subprocess.STDOUT,
       start_new_session=True, env=environment())
    instances.append(('redis', redis))
    rcli = ['redis-cli', '-h', '127.0.0.1', '-p', '16317']
    day17.wait_ready(lambda: subprocess.run(
        rcli + ['PING'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        text=True, timeout=2, env=environment()).stdout.strip() == 'PONG', redis)
    # A port can be claimed after the initial bind probe: verify ownership before SET.
    server_info = day17.run(rcli + ['INFO', 'server'])
    if f'process_id:{redis.pid}' not in server_info.splitlines():
        raise RuntimeError('Redis port is not owned by this invocation; refusing writes')
    day17.run(rcli + ['SET', 'day17:safety-guard', guard])
    return cli, rcli


def safe_configuration(temp):
    config = temp / 'day18-test.properties'
    # spring.config.location REPLACES normal application.properties/profile discovery.
    # This key exists only in the private temporary file, never in invocation logs.
    jwt = base64.b64encode(os.urandom(32)).decode('ascii')
    config.write_text('\n'.join([
        'spring.application.name=resq-day18-isolated',
        'server.address=127.0.0.1',
        'server.port=0',
        'spring.datasource.url=' + MYSQL_URL,
        'spring.datasource.username=day17_test',
        'spring.datasource.password=',
        'spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver',
        'spring.datasource.hikari.maximum-pool-size=16',
        'spring.datasource.hikari.minimum-idle=0',
        'spring.sql.init.mode=never',
        'mybatis.mapper-locations=classpath:mapper/*.xml',
        'spring.data.redis.host=127.0.0.1',
        'spring.data.redis.port=16317',
        'spring.data.redis.database=0',
        'spring.data.redis.username=',
        'spring.data.redis.password=',
        'spring.data.redis.connect-timeout=2s',
        'spring.data.redis.timeout=2s',
        'resq.jwt.secret-base64=' + jwt,
        'resq.jwt.expiration-seconds=3600',
        '',
    ]))
    config.chmod(0o600)
    return config


def verify_guards(cli, rcli, guard, instances):
    if any(process.poll() is not None for _, process in instances):
        raise RuntimeError('An owned private service exited; refusing to run tests')
    actual = day17.run(cli + ['-e',
        'SELECT DATABASE(), @@port, token '
        'FROM resq_day17.day17_safety_guard', 'resq_day17']).strip()
    if actual != f'resq_day17\t13317\t{guard}':
        raise RuntimeError('Private MySQL endpoint/guard verification failed')
    if day17.run(rcli + ['GET', 'day17:safety-guard']).strip() != guard:
        raise RuntimeError('Private Redis guard verification failed')
    redis = next(process for name, process in instances if name == 'redis')
    if f'process_id:{redis.pid}' not in day17.run(rcli + ['INFO', 'server']).splitlines():
        raise RuntimeError('Private Redis process ownership verification failed')


def report_state():
    path = PROJECT / 'target/surefire-reports'
    return {p.name: (p.stat().st_mtime_ns, p.stat().st_size)
            for p in path.glob('*') if p.is_file()}


def run_stage(name, tests, integration, config, guard, instances, cleanup, timeout):
    destination = RESULTS / name
    destination.mkdir()
    before = report_state()
    args = ['mvn', '-B', f'-Dspring.config.location={config.as_uri()}',
            '-Dspring.profiles.active=', '-Dspring.profiles.include=',
            '-Dspring.config.import=', '-Dspring.sql.init.mode=never',
            '-Dresq.day17.integration=' + str(integration).lower(),
            '-Dday17.mysql.url=' + MYSQL_URL, '-Dday17.redis.port=16317',
            '-Dday17.guard=' + guard, '-Dday17.results=' + str(destination)]
    if tests is not None:
        args.append('-Dtest=' + tests)
    args.append('test')
    metadata = {'stage': name, 'selector': tests, 'integration': integration,
                'workingDirectory': str(PROJECT), 'usesProjectPom': True,
                'applicationConfiguration': 'temporary owned private configuration'}
    print('Running isolated stage:', name, flush=True)
    started = time.monotonic()
    with (destination / 'maven.log').open('w') as log:
        proc = subprocess.Popen(args, cwd=PROJECT, env=environment(), stdout=log,
                                stderr=subprocess.STDOUT, start_new_session=True)
        entry = ('maven-' + name, proc)
        instances.append(entry)
        try:
            try:
                code = proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                code = 124
                metadata['timeout'] = True
        finally:
            day17.stop_owned_process(proc)
            instances.remove(entry)
            cleanup['maven-' + name + 'Stopped'] = proc.poll() is not None
    reports = destination / 'surefire-reports'
    reports.mkdir()
    # Snapshot only this invocation's reports; an older failed run must not masquerade as new.
    for filename, stat in report_state().items():
        if before.get(filename) != stat:
            shutil.copy2(PROJECT / 'target/surefire-reports' / filename, reports / filename)
    counts = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for report in reports.glob('TEST-*.xml'):
        root = ET.parse(report).getroot()
        for field in counts:
            counts[field] += int(root.get(field, '0'))
    metadata.update(exitCode=code, elapsedSeconds=round(time.monotonic() - started, 2), **counts)
    (destination / 'summary.json').write_text(json.dumps(metadata, indent=2))
    print(name + ': ' + json.dumps({key: metadata[key] for key in ('exitCode', *counts)}), flush=True)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--include-day17-integration', action='store_true',
                        help='also run the guarded Day17 transaction/concurrency whitelist before the full suite')
    parser.add_argument('--phase', choices=('all', 'groups', 'full', 'integration'), default='all',
                        help='default all: groups, optional Day17, then full suite')
    parser.add_argument('--stage-timeout', type=int, default=600)
    options = parser.parse_args()
    if options.stage_timeout <= 0:
        parser.error('--stage-timeout must be positive')
    if sys.platform != 'linux':
        raise RuntimeError('Run inside WSL Ubuntu')
    for name in ('mysqld', 'mysql', 'redis-server', 'redis-cli', 'mvn', 'java'):
        if shutil.which(name) is None:
            raise RuntimeError('Missing required tool: ' + name)
    day17.check_ports()  # Fail closed; never stop a process occupying the reserved ports.
    RESULTS.mkdir(parents=True)
    protected = day17.file_hashes()
    (RESULTS / 'main-and-pom-before.json').write_text(json.dumps(protected, indent=2))
    cleanup = {'mysqlStopped': False, 'redisStopped': False, 'privateDataRemoved': False}
    instances, handles, stages = [], [], []
    temp_path = None
    exit_code = 1
    print('Day18 isolated results:', RESULTS, flush=True)
    try:
        # Reuse exact /tmp parent/prefix validation and process-group cleanup from Day17.
        with day17.private_workspace(instances, cleanup) as temp:
            temp_path = temp
            temp.chmod(0o700)
            guard = uuid.uuid4().hex
            cli, rcli = start_services(temp, instances, handles, guard)
            config = safe_configuration(temp)
            plan = GROUPS.copy() if options.phase in ('all', 'groups') else []
            if options.phase == 'integration' or (options.phase == 'all' and options.include_day17_integration):
                plan.append(('day17-integration', DAY17))
            if options.phase in ('all', 'full'):
                plan.append(('full-suite', None))
            for name, tests in plan:
                verify_guards(cli, rcli, guard, instances)
                stages.append(run_stage(name, tests, name == 'day17-integration',
                                        config, guard, instances, cleanup, options.stage_timeout))
                verify_guards(cli, rcli, guard, instances)
            exit_code = int(any(stage['exitCode'] != 0 for stage in stages))
    finally:
        day17.stop_owned_instances(instances, cleanup)
        for handle in handles:
            handle.close()
        cleanup['privateDataRemoved'] = temp_path is not None and not temp_path.exists()
        cleanup['mainAndPomUnchangedDuringRun'] = protected == day17.file_hashes()
        (RESULTS / 'cleanup.json').write_text(json.dumps(cleanup, indent=2))
        (RESULTS / 'summary.json').write_text(json.dumps(stages, indent=2))
        if not cleanup['mainAndPomUnchangedDuringRun']:
            print('Production files or pom changed during tests; acceptance needs recheck', file=sys.stderr)
            exit_code = 1
    return exit_code


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print('Day18 isolated runner:', error, file=sys.stderr)
        sys.exit(1)
