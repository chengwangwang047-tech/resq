#!/usr/bin/env python3
"""Prepare a NEW isolated ResQ v1.0 demo session; never uses deploy/.env."""
from pathlib import Path
import argparse
import base64
import hashlib
import json
import os
import secrets
import subprocess
import sys
import time
import common as c


def prepare(timeout_seconds=60):
    c.require(30<=timeout_seconds<=600,'Demo confirmation timeout must be between 30 and 600 seconds')
    started=time.monotonic();guard=secrets.token_hex(16);directory=c.session_path(guard)
    c.ROOT.mkdir(parents=True,exist_ok=True,mode=0o700);os.chmod(c.ROOT,0o700)
    directory.mkdir(mode=0o700)
    state={'version':'ResQ v1.0 demo','guard':guard,'project':'resq-demo-'+guard[:12],
           'image':'resq:demo-'+guard[:12],'timeoutSeconds':timeout_seconds,'ready':False,'modules':{},
           'createdAtUtc':c.datetime.datetime.now(c.datetime.timezone.utc).isoformat(),
           'createdAtEpoch':time.time(),'jarSha256':None,'phase':'initializing',
           'composeBaseHash':hashlib.sha256((c.PROJECT/'deploy/compose.yaml').read_bytes()).hexdigest(),
           'commit':c.invoke(['git','-C',str(c.PROJECT),'rev-parse','HEAD'],'Git revision'),
           'existingRuntime':c.deployment.existing_snapshot(c.env()),'ports':c.deployment.choose_ports()}
    print('SESSION '+guard,flush=True)
    c.require_fresh_names(state)
    c.require(subprocess.run(['docker','image','inspect',state['image']],env=c.env(),capture_output=True).returncode!=0,'Demo image tag already exists')
    settings={'MYSQL_ROOT_PASSWORD':secrets.token_hex(24),'RESQ_DB_USERNAME':'demo_app',
              'RESQ_DB_PASSWORD':secrets.token_hex(24),'RESQ_RABBITMQ_USERNAME':'demo_app',
              'RESQ_RABBITMQ_PASSWORD':secrets.token_hex(24),
              'RESQ_PROD_JWT_SECRET_BASE64':base64.b64encode(secrets.token_bytes(32)).decode(),
              'RESQ_APP_IMAGE':state['image'],'RESQ_TIME_ZONE':'UTC',**state['ports']}
    (directory/'synthetic.env').write_text(''.join(k+'='+str(v)+'\n' for k,v in settings.items()))
    os.chmod(directory/'synthetic.env',0o600)
    labels={c.LABEL:guard}
    overlay={'services':{name:{'labels':labels} for name in c.SERVICES},
             'volumes':{name:{'labels':labels} for name in ('mysql_data','redis_data','rabbitmq_data')},
             'networks':{'resq_net':{'labels':labels}}}
    overlay['services']['app'].update({'build':{'labels':labels},'mem_limit':'1g',
        'environment':{'JAVA_TOOL_OPTIONS':'-XX:ActiveProcessorCount=2 -Xmx512m -Duser.timezone=UTC '
                       '-Dresq.reservation.confirm-timeout-seconds='+str(timeout_seconds)+' '
                       '-Dresq.reservation.expire-scan-initial-delay-ms=86400000'}})
    overlay['services']['mysql'].update({'mem_limit':'768m','command':['--innodb-buffer-pool-size=64M','--max-connections=96']})
    overlay['services']['rabbitmq'].update({'mem_limit':'768m','environment':{
        'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS':'+S 2:2','RABBITMQ_CTL_ERL_ARGS':'+S 1:1'}})
    c.write_json(directory/'override.json',overlay)
    for name,key in (('synthetic.env','envHash'),('override.json','overrideHash')):
        state[key]=hashlib.sha256((directory/name).read_bytes()).hexdigest()
    c.write_json(directory/'credentials.json',{'brokerUser':settings['RESQ_RABBITMQ_USERNAME'],
        'brokerPassword':settings['RESQ_RABBITMQ_PASSWORD'],'pool':[]})
    c.write_json(directory/'state.json',state)
    (c.ROOT/'latest').write_text(guard+'\n');os.chmod(c.ROOT/'latest',0o600)
    command=c.compose(state,directory)
    with c.locked(directory):
        try:
            model=json.loads(c.invoke(command+['config','--format','json'],'Synthetic demo configuration'))
            c.deployment.validate_model(model,state['project'])
            for image in ('mysql:8.0.46','redis:7-alpine','rabbitmq:3.13-management','eclipse-temurin:21-jre-jammy'):
                c.invoke(['docker','image','inspect','--format','{{.Id}}',image],'Required cached image check: '+image)
            print('准备阶段：构建 JAR 并运行默认测试（日志仅本地保存）。',flush=True)
            state['phase']='maven'
            result=c.run_logged(['mvn','-o','-B','package'],directory/'build.log',300)
            c.require(result==0,'Default Maven build failed; inspect this session build.log locally')
            jar=c.PROJECT/'target/resq-0.0.1-SNAPSHOT.jar'
            state['jarSha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
            print('准备阶段：复用专用隔离测试，预先验证失败路由。',flush=True)
            from run_p0_integration_tests import main as p0
            suites=p0(directory/'fault-evidence')
            mq=[s for s in suites if s['name']=='com.resq.mq.TimeoutFailureRoutingIT']
            c.require(len(mq)==1 and mq[0]['tests']=='1' and all(mq[0][k]=='0' for k in ('failures','errors','skipped')),'Failure-routing evidence is incomplete')
            state['failureRoutingEvidence']={'mode':'preflight-dedicated-isolated-test','suite':mq[0],
                'allDedicatedTests':8,'timestampUtc':c.datetime.datetime.now(c.datetime.timezone.utc).isoformat(),
                'scope':'separate disposable broker; mocked service; not consumer database idempotency'}
            print('准备阶段：构建独立镜像并启动三个演示依赖。',flush=True)
            state['phase']='dependencies'
            c.startup.invoke(command+['build','app'],c.env(),'Demo image build',timeout=600)
            c.startup.invoke(command+['up','-d','--wait','--wait-timeout','240','mysql','redis','rabbitmq'],c.env(),'Demo dependencies',timeout=300)
            c.verify_all(state)
            identity=c.container(state,directory,'mysql')
            state['seed']=c.seeds.seed(identity,guard)
            print('[PASS] 已种入 2 个资源和 8 个未来时隙。',flush=True)
            state['phase']='application'
            c.startup.start(command,c.env())
            state['containers']={name:c.container(state,directory,name) for name in c.SERVICES}
            c.require(c.http(state,'GET','/actuator/health')['status']=='UP','Health is not UP')
            image_id=c.invoke(['docker','image','inspect','--format','{{.Id}}',state['image']],'Demo image identity')
            c.require(c.invoke(['docker','inspect','--format','{{.Image}}',state['containers']['app']],'App image identity')==image_id,'Unexpected running app image')
            state['imageId']=image_id
            print('准备阶段：通过 HTTP 注册 50 个独立并发用户。',flush=True)
            credentials=json.loads((directory/'credentials.json').read_text())
            for number in range(50):credentials['pool'].append(c.register(state,'pool'+str(number)))
            c.write_json(directory/'credentials.json',credentials)
            state['ready']=True;state['phase']='ready';state['prepareDurationSeconds']=round(time.monotonic()-started,2)
            state['runtimePreservation']=c.preserve_check(state)
            print('[PASS] 四服务健康；准备完成，用时 '+str(state['prepareDurationSeconds'])+' 秒。',flush=True)
            print('URL http://127.0.0.1:'+str(state['ports']['RESQ_APP_PORT']),flush=True)
        except BaseException as error:
            state['prepareFailure']=type(error).__name__
            print('准备失败，现场保留；可用 run.py cleanup --session '+guard+' 清理本次资源。',flush=True)
            raise
        finally:
            c.write_json(directory/'state.json',state);c.save_report(state,directory)
    print('REPORT '+str(directory/'report.md'),flush=True)
    return state


if __name__=='__main__':
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout-seconds',type=int,default=60)
    args=parser.parse_args()
    try:prepare(args.timeout_seconds)
    except Exception as error:
        print('PREPARE FAILED: '+(str(error) if isinstance(error,c.DemoError) else type(error).__name__),file=sys.stderr)
        sys.exit(1)
