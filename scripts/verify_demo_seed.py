#!/usr/bin/env python3
"""Verify the demo seed in one new disposable MySQL; not a four-service demo launcher."""
from pathlib import Path
import datetime
import json
import os
import secrets
import subprocess
import sys
import time

import seed_demo_data as seed
from run_integration_tests import clean_environment, run
from run_p0_integration_tests import cleanup_owned
from verify_compose_deployment import existing_snapshot

PROJECT=Path(__file__).resolve().parents[1]


def main():
    env=clean_environment()
    run(['docker','image','inspect','--format','{{.Id}}','mysql:8.0.46'],env=env)
    before=existing_snapshot(env)
    guard=secrets.token_hex(16); project='resq-demo-'+guard[:12]
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    results=PROJECT/'target/demo-seed-results'/(stamp+'-'+guard[:12])
    results.mkdir(parents=True,mode=0o700)
    child=dict(env,MYSQL_ROOT_PASSWORD=secrets.token_urlsafe(24),MYSQL_DATABASE='resq',
               MYSQL_USER='demo_seed',MYSQL_PASSWORD=secrets.token_urlsafe(24))
    owned=[];checks=[];summary={'passed':False,'checks':checks}
    def checked(name):checks.append(name);print('PASS '+name,flush=True)
    try:
        identity=run(['docker','create','--name',project+'-mysql',
                      '--label','com.docker.compose.project='+project,
                      '--label','com.docker.compose.service=mysql',
                      '--label',seed.LABEL+'='+guard,'--label','resq.p0.guard='+guard,
                      '--tmpfs','/var/lib/mysql:rw','--memory','1g',
                      '-e','MYSQL_ROOT_PASSWORD','-e','MYSQL_DATABASE','-e','MYSQL_USER','-e','MYSQL_PASSWORD',
                      'mysql:8.0.46','--innodb-buffer-pool-size=64M','--max-connections=30',
                      '--default-time-zone=+00:00'],env=child)
        if len(identity)!=64:raise RuntimeError('Unexpected Docker identity')
        owned.append(identity)
        run(['docker','start',identity],env=env)
        seed.verify_target(identity,guard,env)
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            try:
                seed.execute_sql(identity,'SELECT 1;',env);break
            except RuntimeError:time.sleep(1)
        else:raise RuntimeError('Disposable demo MySQL did not become ready')
        seed.execute_sql(identity,(PROJECT/'deploy/init/01-schema.sql').read_text(),env)
        checked('fresh-current-schema-in-owned-tmpfs-mysql')
        counts='SELECT (SELECT COUNT(*) FROM compute_resource),(SELECT COUNT(*) FROM resource_slot),(SELECT COUNT(*) FROM `user`),(SELECT COUNT(*) FROM reservation),(SELECT COUNT(*) FROM mq_consume_record);'
        original=seed.execute_sql(identity,counts,env)
        assert original=='0\t0\t0\t0\t0'
        bad=seed.build_seed_sql().replace('COMMIT;','INSERT INTO resq_demo_seed_check VALUES(0);\nCOMMIT;',1)
        try:seed.execute_sql(identity,bad,env)
        except RuntimeError:pass
        else:raise RuntimeError('Injected transaction failure was not detected')
        assert seed.execute_sql(identity,counts,env)==original
        checked('late-seed-failure-rolls-back-all-business-rows')
        cli=[sys.executable,str(PROJECT/'scripts/seed_demo_data.py'),'--container',identity,'--guard',guard]
        output=run(cli,env=env)
        manifest=json.loads(output)
        assert manifest['version']==seed.VERSION and manifest['usersCreated']==manifest['reservationsCreated']==0
        assert len(manifest['resources'])==2 and len(manifest['slots'])==8
        assert {s['scenario'] for s in manifest['slots']}=={s[0] for s in seed.SCENARIOS}
        assert len({s['id'] for s in manifest['slots']})==8
        assert {s['resourceId'] for s in manifest['slots']}=={r['id'] for r in manifest['resources']}
        assert seed.execute_sql(identity,counts,env)=='2\t8\t0\t0\t0'
        assert seed.execute_sql(identity,"SELECT COUNT(*) FROM resource_slot WHERE status='AVAILABLE' AND slot_start>UTC_TIMESTAMP() AND slot_end>slot_start;",env)=='8'
        checked('public-cli-creates-two-resources-eight-future-slots-and-id-manifest')
        snapshot_sql="SELECT id,resource_id,slot_start,slot_end,status FROM resource_slot ORDER BY id;SELECT id,name,type,status,created_at FROM compute_resource ORDER BY id;"
        snapshot=seed.execute_sql(identity,snapshot_sql,env)
        second=subprocess.run(cli,env=env,capture_output=True,text=True)
        assert second.returncode!=0
        assert seed.execute_sql(identity,snapshot_sql,env)==snapshot
        assert seed.execute_sql(identity,counts,env)=='2\t8\t0\t0\t0'
        checked('second-seed-refuses-nonempty-database-without-changing-rows')
        wrong=cli[:-1]+[secrets.token_hex(16)]
        assert subprocess.run(wrong,env=env,capture_output=True).returncode!=0
        assert seed.execute_sql(identity,snapshot_sql,env)==snapshot
        checked('wrong-demo-guard-refuses-without-changing-rows')
        (results/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        summary['passed']=True
    finally:
        try:
            removed=cleanup_owned(owned,guard,env)
            summary['removedOwnedContainerIds']=removed
            after=existing_snapshot(env)
            summary['existingContainersUnchanged']=all(after['containers'].get(k)==v for k,v in before['containers'].items())
            summary['existingVolumesPreserved']=set(before['volumes']).issubset(after['volumes'])
            summary['ownedContainersGone']=not(set(removed)&set(after['containers']))
            if not all(summary[k] for k in ('existingContainersUnchanged','existingVolumesPreserved','ownedContainersGone')):
                summary['passed']=False
                raise RuntimeError('Runtime preservation check failed')
        except BaseException:
            summary['passed']=False
            raise
        finally:(results/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print('RESULTS '+str(results/'summary.json'),flush=True)
    return summary


if __name__=='__main__':
    os.umask(0o077)
    try:main()
    except (RuntimeError,OSError,ValueError,AssertionError,subprocess.SubprocessError) as error:
        print('Demo seed verification failed ('+type(error).__name__+'); inspect target/demo-seed-results.',file=sys.stderr)
        sys.exit(1)
