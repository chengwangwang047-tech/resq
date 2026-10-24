#!/usr/bin/env python3
"""Seed two resources and eight future slots in an empty, explicitly owned demo MySQL only."""
import argparse
import json
import re
import subprocess
import sys

from run_integration_tests import clean_environment

LABEL = 'resq.demo.guard'
VERSION = 'resq-demo-seed-v1'
# role, resource variable, days after UTC today, hour. Separate dates isolate slot cache keys.
SCENARIOS = (
    ('basic-confirm', 'gpu', 1, 9),
    ('basic-cancel', 'cpu', 1, 10),
    ('concurrency', 'gpu', 2, 9),
    ('idempotency', 'gpu', 3, 9),
    ('idempotency-conflict', 'gpu', 3, 10),
    ('timeout', 'gpu', 4, 9),
    ('cache', 'gpu', 5, 9),
    ('waitlist', 'gpu', 6, 9),
)


def command(args, env, data=None):
    result = subprocess.run(args, env=env, input=data, capture_output=True, text=True, timeout=40)
    if result.returncode:
        # Never expose command stderr: it can contain database/session information.
        raise RuntimeError('Demo operation refused or failed; no raw command output is disclosed')
    return result.stdout.strip()


def inspect(container, template, env):
    return command(['docker', 'inspect', '--format', template, container], env)


def verify_target(container, guard, env):
    if not re.fullmatch(r'[a-f0-9]{64}', container) or not re.fullmatch(r'[a-f0-9]{32}', guard):
        raise RuntimeError('An exact container ID and a 32-character random demo guard are required')
    project = 'resq-demo-' + guard[:12]
    template = ('{{index .Config.Labels "com.docker.compose.project"}}|'
                '{{index .Config.Labels "com.docker.compose.service"}}|'
                '{{index .Config.Labels "' + LABEL + '"}}|{{.State.Running}}|{{.Config.Image}}')
    if inspect(container, template, env) != '|'.join((project, 'mysql', guard, 'true', 'mysql:8.0.46')):
        raise RuntimeError('Only a running, explicitly marked fresh demo MySQL is accepted')
    # A renamed/labeled container must not secretly mount an existing development volume.
    tmpfs = json.loads(inspect(container, '{{json .HostConfig.Tmpfs}}', env)) or {}
    mounts = json.loads(inspect(container, '{{json .Mounts}}', env))
    data_mounts = [m for m in mounts if m['Destination'] == '/var/lib/mysql']
    if '/var/lib/mysql' in tmpfs:
        if any(m['Type'] != 'tmpfs' for m in data_mounts):
            raise RuntimeError('Unexpected MySQL data mount')
    else:
        expected_name = project + '_mysql_data'
        if (len(data_mounts) != 1 or data_mounts[0]['Type'] != 'volume'
                or data_mounts[0]['Name'] != expected_name):
            raise RuntimeError('A project-owned demo volume or tmpfs is required')
        actual = command(['docker', 'volume', 'inspect', '--format',
                          '{{index .Labels "com.docker.compose.project"}}|{{index .Labels "' + LABEL + '"}}',
                          expected_name], env)
        if actual != project + '|' + guard:
            raise RuntimeError('Demo volume ownership does not match')
    apps = command(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
                    '--filter', 'label=com.docker.compose.service=app'], env)
    if apps:
        raise RuntimeError('Stop the demo application before first-time seeding')
    return project


def execute_sql(container, statement, env):
    # Credentials stay inside the owned container, not argv/host output; ignore client defaults.
    client = ('set -eu; MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --no-defaults '
              '--protocol=TCP --host=127.0.0.1 --port=3306 --user="$MYSQL_USER" '
              '--database=resq --batch --raw --skip-column-names')
    return command(['docker', 'exec', '-i', container, 'sh', '-c', client], env, statement)


def build_seed_sql():
    empty = ' AND '.join('(SELECT COUNT(*) FROM `' + table + '`)=0'
                         for table in ('user','compute_resource','resource_slot','reservation','mq_consume_record'))
    sql = ["SET SESSION time_zone='+00:00';",
           'CREATE TEMPORARY TABLE resq_demo_seed_check (ok TINYINT NOT NULL CHECK (ok=1)) ENGINE=InnoDB;',
           'START TRANSACTION;',
           "INSERT INTO resq_demo_seed_check SELECT IF(GET_LOCK('resq.demo.seed.v1',0)=1 AND " + empty +
           " AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
           " AND table_type='BASE TABLE' AND engine='InnoDB')=5"
           " AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
           " AND table_type='BASE TABLE')=5,1,0);",
           "INSERT INTO compute_resource(name,type,status,created_at) VALUES('ResQ Demo GPU','GPU','AVAILABLE',UTC_TIMESTAMP());",
           'SET @gpu=LAST_INSERT_ID();',
           "INSERT INTO compute_resource(name,type,status,created_at) VALUES('ResQ Demo CPU','CPU','AVAILABLE',UTC_TIMESTAMP());",
           'SET @cpu=LAST_INSERT_ID();']
    outputs = ["SELECT JSON_OBJECT('kind','resource','role','gpu','id',@gpu,'type','GPU');",
               "SELECT JSON_OBJECT('kind','resource','role','cpu','id',@cpu,'type','CPU');"]
    for i,(role,resource,days,hour) in enumerate(SCENARIOS):
        start = f'(UTC_DATE()+INTERVAL {days} DAY+INTERVAL {hour} HOUR)'
        end = f'(UTC_DATE()+INTERVAL {days} DAY+INTERVAL {hour+1} HOUR)'
        sql += [f"INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) VALUES(@{resource},{start},{end},'AVAILABLE');",
                f'SET @slot{i}=LAST_INSERT_ID();']
        outputs.append(f"SELECT JSON_OBJECT('kind','slot','scenario','{role}','id',@slot{i},'resourceId',resource_id,"
                       "'slotStart',DATE_FORMAT(slot_start,'%Y-%m-%dT%H:%i:%s'),"
                       "'slotEnd',DATE_FORMAT(slot_end,'%Y-%m-%dT%H:%i:%s'),'status',status) "
                       f'FROM resource_slot WHERE id=@slot{i};')
    sql += ["INSERT INTO resq_demo_seed_check SELECT IF((SELECT COUNT(*) FROM compute_resource)=2"
            " AND (SELECT COUNT(*) FROM resource_slot WHERE status='AVAILABLE' AND slot_start>UTC_TIMESTAMP()"
            " AND slot_end>slot_start)=8,1,0);", 'COMMIT;', *outputs]
    return '\n'.join(sql)+'\n'


def seed(container, guard):
    env = clean_environment()
    project = verify_target(container, guard, env)
    rows = [json.loads(line) for line in execute_sql(container, build_seed_sql(), env).splitlines()]
    resources = [r for r in rows if r['kind']=='resource']
    slots = [r for r in rows if r['kind']=='slot']
    if len(resources)!=2 or len(slots)!=8 or {r['scenario'] for r in slots}!={r[0] for r in SCENARIOS}:
        raise RuntimeError('Unexpected seed result; inspect the isolated demo before retrying')
    return {'version':VERSION,'project':project,'timeZone':'UTC','resources':resources,'slots':slots,
            'usersCreated':0,'reservationsCreated':0}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container',required=True,help='Exact ID of the explicitly labeled demo MySQL')
    parser.add_argument('--guard',required=True,help='Random demo ownership marker, not an authentication secret')
    args=parser.parse_args()
    try:
        print(json.dumps(seed(args.container,args.guard),ensure_ascii=False,indent=2))
    except (RuntimeError,OSError,ValueError,KeyError,subprocess.SubprocessError) as error:
        print('Seed refused or failed ('+type(error).__name__+'). Requires an empty, owned demo database; '
              'no existing data is cleared or overwritten.',file=sys.stderr)
        sys.exit(1)
