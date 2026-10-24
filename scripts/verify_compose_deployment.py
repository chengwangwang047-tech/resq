#!/usr/bin/env python3
"""Exercise the real four-service Compose deployment using synthetic, isolated data only."""
from pathlib import Path
import argparse
import base64
import datetime
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time
import traceback

import start_compose as deployment
from http_probe import request_json

PROJECT = Path(__file__).resolve().parents[1]
GUARD_LABEL = 'resq.acceptance.guard'
PORT_KEYS = ('RESQ_APP_PORT', 'RESQ_MYSQL_PORT', 'RESQ_REDIS_PORT',
             'RESQ_RABBITMQ_PORT', 'RESQ_RABBITMQ_MANAGEMENT_PORT')


def clean_environment():
    prefixes = ('RESQ_', 'SPRING_', 'MYSQL_', 'REDIS_', 'RABBITMQ_', 'COMPOSE_')
    removed = {'MYSQL_PWD', 'REDISCLI_AUTH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
               '_JAVA_OPTIONS', 'MAVEN_ARGS', 'MAVEN_OPTS'}
    return {key: value for key, value in os.environ.items()
            if not key.startswith(prefixes) and key not in removed}


def assert_isolated_project(project, guard):
    if not re.fullmatch(r'[a-f0-9]{32}', guard) or project != 'resq-compose-it-' + guard[:12]:
        raise RuntimeError('Refusing a non-isolated Compose project')


def assert_ownership(kind, project, guard, actual_project, actual_guard):
    assert_isolated_project(project, guard)
    if actual_project != project or actual_guard != guard:
        raise RuntimeError('Refusing action on a ' + kind + ' with mismatched ownership')


def validate_model(model, project):
    if model.get('name') != project or set(model['services']) != {'app', 'mysql', 'redis', 'rabbitmq'}:
        raise RuntimeError('Unexpected Compose deployment shape')
    for logical in ('mysql_data', 'redis_data', 'rabbitmq_data'):
        volume = model['volumes'][logical]
        if volume.get('external') or volume.get('name') != project + '_' + logical:
            raise RuntimeError('Refusing a reused or external data volume')
    network = model['networks']['resq_net']
    if network.get('external') or network.get('name') != project + '_resq_net':
        raise RuntimeError('Refusing a reused or external network')
    expected = {'RESQ_PROD_REDIS_HOST': 'redis', 'RESQ_PROD_RABBITMQ_HOST': 'rabbitmq',
                'SPRING_PROFILES_ACTIVE': 'prod'}
    app = model['services']['app']['environment']
    if any(app.get(key) != value for key, value in expected.items()) or not app['RESQ_PROD_DB_URL'].startswith('jdbc:mysql://mysql:3306/resq?'):
        raise RuntimeError('Unexpected application connection endpoints')


def run(command, env, label, data=None, timeout=300):
    result = subprocess.run(command, env=env, input=data, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(label + ' failed (exit ' + str(result.returncode) + ')')
    return result.stdout.strip()


def resources(project, env):
    result = {}
    for kind in ('container', 'network', 'volume'):
        command = ['docker', kind, 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project]
        if kind == 'container':
            command += ['-a', '--no-trunc']
        result[kind] = run(command, env, 'Project resource lookup').splitlines()
    return result


def verify_resource(kind, identity, project, guard, env):
    label_path = '.Config.Labels' if kind == 'container' else '.Labels'
    template = '{{index ' + label_path + ' "com.docker.compose.project"}}|{{index ' + label_path + ' "' + GUARD_LABEL + '"}}'
    labels = run(['docker', kind, 'inspect', '--format', template, identity], env, 'Ownership lookup').split('|')
    if len(labels) != 2:
        raise RuntimeError('Invalid ownership metadata')
    assert_ownership(kind, project, guard, *labels)


def cleanup(command, env, project, guard):
    assert_isolated_project(project, guard)
    owned = resources(project, env)
    # Validate EVERY resource before performing any destructive operation.
    for kind, identities in owned.items():
        for identity in identities:
            verify_resource(kind, identity, project, guard, env)
    run(command + ['down', '--volumes', '--remove-orphans', '--timeout', '20'], env, 'Owned project cleanup')
    if any(resources(project, env).values()):
        raise RuntimeError('Owned project cleanup was incomplete')
    return {'removed': {kind: len(values) for kind, values in owned.items()}, 'ownershipVerified': True}


def existing_snapshot(env):
    identities = run(['docker', 'ps', '-aq', '--no-trunc'], env, 'Existing container inventory').splitlines()
    template = '{{.Name}}|{{.State.Status}}|{{.RestartCount}}'
    containers = {identity: run(['docker', 'inspect', '--format', template, identity], env, 'Existing runtime metadata')
                  for identity in identities}
    volumes = run(['docker', 'volume', 'ls', '-q'], env, 'Existing volume inventory').splitlines()
    return {'containers': containers, 'volumes': volumes}


def choose_ports():
    sockets = []
    try:
        for _ in PORT_KEYS:
            sock = socket.socket()
            sock.bind(('127.0.0.1', 0))
            sockets.append(sock)
        return dict(zip(PORT_KEYS, (sock.getsockname()[1] for sock in sockets)))
    finally:
        for sock in sockets:
            sock.close()


def main(keep_image=False):
    if sys.platform != 'linux':
        raise RuntimeError('Run inside WSL Ubuntu')
    if not (PROJECT / 'target/resq-0.0.1-SNAPSHOT.jar').is_file():
        raise RuntimeError('Run mvn clean package first')
    env = clean_environment()
    guard = secrets.token_hex(16)
    project = 'resq-compose-it-' + guard[:12]
    assert_isolated_project(project, guard)
    if any(resources(project, env).values()):
        raise RuntimeError('Generated project already exists; refusing reuse')
    before = existing_snapshot(env)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    results = PROJECT / 'target/compose-acceptance' / (stamp + '-' + guard[:12])
    results.mkdir(parents=True, mode=0o700)
    private = results / 'private'
    private.mkdir(mode=0o700)
    image = 'resq:compose-it-' + guard[:12]
    if subprocess.run(['docker', 'image', 'inspect', image], env=env, capture_output=True).returncode == 0:
        raise RuntimeError('Generated image tag already exists')
    ports = choose_ports()
    db_password = secrets.token_hex(24)
    settings = {'MYSQL_ROOT_PASSWORD': secrets.token_hex(24), 'RESQ_DB_USERNAME': 'compose_it',
                'RESQ_DB_PASSWORD': db_password, 'RESQ_RABBITMQ_USERNAME': 'compose_it',
                'RESQ_RABBITMQ_PASSWORD': secrets.token_hex(24),
                'RESQ_PROD_JWT_SECRET_BASE64': base64.b64encode(secrets.token_bytes(32)).decode(),
                'RESQ_APP_IMAGE': image, 'RESQ_TIME_ZONE': 'UTC', **ports}
    env_file = private / 'synthetic.env'
    env_file.write_text(''.join(key + '=' + str(value) + '\n' for key, value in settings.items()))
    env_file.chmod(0o600)
    labels = {GUARD_LABEL: guard}
    overlay = {'services': {name: {'labels': labels} for name in ('app', 'mysql', 'redis', 'rabbitmq')},
               'volumes': {name: {'labels': labels} for name in ('mysql_data', 'redis_data', 'rabbitmq_data')},
               'networks': {'resq_net': {'labels': labels}}}
    overlay['services']['app'].update({'build': {'labels': labels}, 'mem_limit': '1g',
        'environment': {'JAVA_TOOL_OPTIONS': '-XX:ActiveProcessorCount=2 -Xmx512m -Duser.timezone=UTC'}})
    overlay['services']['mysql'].update({'mem_limit': '768m',
        'command': ['--innodb-buffer-pool-size=64M', '--max-connections=64']})
    overlay['services']['rabbitmq'].update({'mem_limit': '768m', 'environment': {
        'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS': '+S 2:2', 'RABBITMQ_CTL_ERL_ARGS': '+S 1:1'}})
    override = private / 'compose-acceptance.json'
    override.write_text(json.dumps(overlay))
    override.chmod(0o600)
    command = deployment.compose_command(project, env_file, override)
    summary = {'project': project, 'image': image, 'checks': [], 'passed': False}

    def checked(name):
        summary['checks'].append(name)
        print('PASS ' + name, flush=True)

    def container(service):
        identity = run(command + ['ps', '-a', '-q', service], env, 'Service identity')
        if not re.fullmatch(r'[a-f0-9]{64}', identity):
            raise RuntimeError('Expected one service container')
        verify_resource('container', identity, project, guard, env)
        return identity

    def sql(statement):
        child = dict(env, MYSQL_PWD=db_password)
        return run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', container('mysql'), 'mysql',
                    '--protocol=TCP', '-h127.0.0.1', '-ucompose_it', '-Dresq', '-N'], child,
                   'Synthetic database operation', data=statement)

    def request(method, path, expected=200, payload=None, token=None, request_id=None):
        status, body = request_json('http://127.0.0.1:' + str(ports['RESQ_APP_PORT']),
                                    method, path, payload, token, request_id, timeout=10)
        if status != expected:
            raise RuntimeError(method + ' ' + path + ' returned unexpected HTTP ' + str(status))
        return body

    def ready(timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                if request('GET', '/actuator/health').get('status') == 'UP':
                    return
            except (OSError, RuntimeError, ValueError):
                pass
            time.sleep(2)
        raise RuntimeError('Application did not become healthy')

    try:
        # This expanded configuration contains SYNTHETIC values only and is never written/printed.
        model = json.loads(run(command + ['config', '--format', 'json'], env, 'Synthetic model validation'))
        validate_model(model, project)
        checked('isolated-project-network-and-volumes')
        # An empty required field must fail without creating a container or disclosing a value.
        missing = private / 'missing.env'
        missing.write_text(''.join(key + '=' + str(value) + '\n' for key, value in settings.items()
                                   if key != 'RESQ_PROD_JWT_SECRET_BASE64'))
        missing.chmod(0o600)
        missing_command = deployment.compose_command(project, missing, override)
        refusal = subprocess.run(missing_command + ['config', '--quiet'], env=env, capture_output=True)
        if refusal.returncode == 0 or any(resources(project, env).values()):
            raise RuntimeError('Missing configuration did not fail before creation')
        missing.unlink()
        checked('missing-required-setting-refused-before-start')
        deployment.start(command, env, build=True)
        checked('four-services-healthy-and-policy-configured')
        for service in ('app', 'mysql', 'redis', 'rabbitmq'):
            template = '{{.State.Health.Status}}|{{.HostConfig.RestartPolicy.Name}}|{{.HostConfig.LogConfig.Type}}|{{index .HostConfig.LogConfig.Config "max-size"}}|{{index .HostConfig.LogConfig.Config "max-file"}}'
            actual = run(['docker', 'inspect', '--format', template, container(service)], env, 'Runtime configuration check')
            assert actual == 'healthy|unless-stopped|json-file|10m|3'
        assert run(['docker', 'exec', container('app'), 'id', '-u'], env, 'Application user check') == '10001'
        checked('runtime-health-restart-log-rotation-and-non-root-user')
        ready()
        assert request('GET', '/api/resources') == []
        assert sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();') == '5'
        checked('fresh-production-schema-initialized')
        request('GET', '/api/reservations/me', expected=401)
        request('POST', '/api/resources', expected=401, payload={'name': 'anonymous', 'type': 'GPU'})
        credentials = {'username': 'compose_' + guard[:12], 'password': secrets.token_urlsafe(24)}
        request('POST', '/api/auth/register', expected=201,
                payload={**credentials, 'email': guard[:12] + '@example.invalid'})
        token = request('POST', '/api/auth/login', payload=credentials)['token']
        resource = request('POST', '/api/resources', expected=201, token=token,
                           payload={'name': 'compose-' + guard[:12], 'type': 'GPU'})
        resource_id = int(resource['id'])
        slot_id = int(sql("INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) VALUES(" +
                          str(resource_id) + ",'2099-01-01 09:00:00','2099-01-01 10:00:00','AVAILABLE');SELECT LAST_INSERT_ID();"))
        slots_path = '/api/resources/' + str(resource_id) + '/slots?date=2099-01-01'
        assert request('GET', slots_path)[0]['status'] == 'AVAILABLE'
        cache_key = 'resource:' + str(resource_id) + ':slots:2099-01-01'
        assert run(['docker', 'exec', container('redis'), 'redis-cli', 'EXISTS', cache_key], env, 'Cache check') == '1'
        checked('jwt-registration-login-resource-and-cache')
        key = 'compose-' + guard[:12]
        first = request('POST', '/api/reservations', expected=201, token=token,
                        request_id=key, payload={'slotId': slot_id})
        repeated = request('POST', '/api/reservations', expected=201, token=token,
                           request_id=key, payload={'slotId': slot_id})
        assert first['id'] == repeated['id'] and first['status'] == 'PENDING'
        cancel_path = '/api/reservations/' + str(first['id'])
        assert request('DELETE', cancel_path, token=token)['status'] == 'CANCELLED'
        request('DELETE', cancel_path, expected=409, token=token)
        assert request('GET', slots_path)[0]['status'] == 'AVAILABLE'
        second = request('POST', '/api/reservations', expected=201, token=token,
                         request_id=key + '-confirm', payload={'slotId': slot_id})
        assert request('POST', '/api/reservations/' + str(second['id']) + '/confirm', token=token)['status'] == 'CONFIRMED'
        checked('reservation-idempotency-cancellation-and-confirmation')
        app = container('app')
        template = '{{.RestartCount}}'
        previous = int(run(['docker', 'inspect', '--format', template, app], env, 'Restart baseline'))
        subprocess.run(['docker', 'exec', app, 'sh', '-c', 'kill -TERM 1'], env=env, capture_output=True)
        deadline = time.monotonic() + 120
        while int(run(['docker', 'inspect', '--format', template, app], env, 'Restart observation')) <= previous:
            if time.monotonic() > deadline:
                raise RuntimeError('Container did not restart after application exit')
            time.sleep(2)
        ready()
        assert len(request('GET', '/api/reservations/me', token=token)) == 2
        checked('application-process-exit-auto-restart')
        marker = 'resq:compose:guard:' + guard
        run(['docker', 'exec', container('redis'), 'redis-cli', 'SET', marker, guard], env, 'Redis persistence marker')
        run(command + ['stop', '--timeout', '30'], env, 'Stop only the isolated stack')
        run(command + ['up', '-d', '--force-recreate', '--wait', '--wait-timeout', '240',
                       'mysql', 'redis', 'rabbitmq'], env, 'Recreate isolated dependencies')
        assert sql('SELECT COUNT(*) FROM reservation;') == '2'
        assert run(['docker', 'exec', container('redis'), 'redis-cli', 'GET', marker], env, 'Redis persistence check') == guard
        policies = json.loads(run(['docker', 'exec', '--user', 'rabbitmq', container('rabbitmq'),
                                   'rabbitmqctl', '--formatter=json', 'list_policies'], env, 'Persisted policy lookup'))
        deployment.timeout_policy.check_existing(policies)
        assert any(p['name'] == deployment.timeout_policy.POLICY_NAME for p in policies)
        run(command + ['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '240', 'app'],
            env, 'Recreate isolated application')
        ready()
        assert len(request('GET', '/api/reservations/me', token=token)) == 2
        checked('mysql-redis-and-broker-state-survive-container-recreation')
        summary['passed'] = True
    except Exception as error:
        location = traceback.extract_tb(error.__traceback__)[-1]
        summary['failure'] = {'type': type(error).__name__, 'file': Path(location.filename).name,
                              'line': location.lineno, 'function': location.name}
        if isinstance(error, RuntimeError):
            summary['failure']['stage'] = str(error)
        raise
    finally:
        try:
            summary['cleanup'] = cleanup(command, env, project, guard)
        except Exception as cleanup_error:
            summary['cleanupRefused'] = type(cleanup_error).__name__
            (results / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
            raise
        after = existing_snapshot(env)
        summary['existingContainersUnchanged'] = all(after['containers'].get(key) == value
                                                    for key, value in before['containers'].items())
        summary['existingVolumesPreserved'] = set(before['volumes']).issubset(after['volumes'])
        summary['imageRetained'] = keep_image
        if not keep_image:
            probe = subprocess.run(['docker', 'image', 'inspect', '--format',
                                     '{{index .Config.Labels "' + GUARD_LABEL + '"}}', image],
                                    env=env, capture_output=True, text=True)
            if probe.returncode == 0:
                if probe.stdout.strip() != guard:
                    raise RuntimeError('Refusing removal of an image with mismatched ownership')
                run(['docker', 'image', 'rm', image], env, 'Owned image cleanup')
        (results / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        for path in private.iterdir():
            path.unlink()
        private.rmdir()
        print('RESULTS ' + str(results / 'summary.json'), flush=True)
    if not summary['existingContainersUnchanged'] or not summary['existingVolumesPreserved']:
        raise RuntimeError('Existing runtime changed during acceptance; review the summary')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keep-image', action='store_true', help='Keep the uniquely tagged tested image for review')
    args = parser.parse_args()
    try:
        outcome = main(args.keep_image)
        print('COMPOSE_ACCEPTANCE_PASSED', flush=True)
    except (RuntimeError, OSError, ValueError, KeyError, AssertionError, subprocess.SubprocessError) as error:
        print('Compose acceptance failed (' + type(error).__name__ + '); inspect target/compose-acceptance.', file=sys.stderr)
        sys.exit(1)
