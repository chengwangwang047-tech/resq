"""Session, ownership and safe I/O for the ResQ command-line demo. No business logic."""
from pathlib import Path
import base64
import contextlib
import datetime
import fcntl
import hashlib
import json
import os
import re
import secrets
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SCRIPTS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(SCRIPTS))
import start_compose as startup
import verify_compose_deployment as deployment
import seed_demo_data as seeds
from http_probe import request_json,open_direct,TransportError

PROJECT=SCRIPTS.parent
ROOT=Path.home()/'.local/share/resq/demo'
LABEL='resq.demo.guard'
SERVICES={'app','mysql','redis','rabbitmq'}


class DemoError(RuntimeError):pass


def require(condition,message):
    if not condition:raise DemoError(message)


def env():
    value=deployment.clean_environment();value['MAVEN_SKIP_RC']='true'
    return value


def invoke(args,label='Demo operation',data=None,timeout=300):
    try:return deployment.run(args,env(),label,data=data,timeout=timeout)
    except RuntimeError as error:raise DemoError(str(error)) from None
    except (OSError,subprocess.SubprocessError) as error:
        raise DemoError(label+' failed ('+type(error).__name__+')') from None


def run_logged(args,log_path,timeout):
    with log_path.open('w') as log:
        process=subprocess.Popen(args,cwd=PROJECT,env=env(),stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try:
            process.wait(timeout=timeout)
            return process.returncode
        finally:
            try:os.killpg(process.pid,signal.SIGTERM)
            except ProcessLookupError:pass
            if process.poll() is None:
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=5)


def write_json(path,value):
    temporary=path.with_name(path.name+'.tmp')
    with temporary.open('w') as stream:
        os.chmod(temporary,0o600);json.dump(value,stream,ensure_ascii=False,indent=2);stream.write('\n')
    temporary.replace(path)


def session_path(identity):
    require(isinstance(identity,str) and re.fullmatch(r'[a-f0-9]{32}',identity),'Invalid session ID')
    require(not ROOT.is_symlink(),'Demo root must not be a symlink')
    path=ROOT/identity
    require(not path.is_symlink(),'Session must not be a symlink')
    return path


def load(identity=None):
    if identity is None:
        require((ROOT/'latest').is_file(),'No demo session; run prepare.py first')
        identity=(ROOT/'latest').read_text().strip()
    directory=session_path(identity)
    state=json.loads((directory/'state.json').read_text())
    require(state['guard']==identity and state['project']=='resq-demo-'+identity[:12],'Session ownership is invalid')
    if not state.get('cleaned'):
        if 'composeBaseHash' in state:
            require(hashlib.sha256((PROJECT/'deploy/compose.yaml').read_bytes()).hexdigest()==state['composeBaseHash'],'Base Compose changed; refusing actions')
        for name,key in (('synthetic.env','envHash'),('override.json','overrideHash')):
            require(hashlib.sha256((directory/name).read_bytes()).hexdigest()==state[key],'Session config changed; refusing actions')
        model=json.loads(invoke(compose(state,directory)+['config','--format','json'],'Session configuration check'))
        deployment.validate_model(model,state['project'])
        for service,key in (('app','RESQ_APP_PORT'),('mysql','RESQ_MYSQL_PORT'),('redis','RESQ_REDIS_PORT'),('rabbitmq','RESQ_RABBITMQ_PORT')):
            ports=model['services'][service]['ports']
            expected=state['ports'][key]
            require(any(p.get('host_ip')=='127.0.0.1' and int(p['published'])==expected for p in ports),'Session port metadata changed')
    return state,directory


@contextlib.contextmanager
def locked(directory):
    with (directory/'session.lock').open('a') as stream:
        os.chmod(directory/'session.lock',0o600)
        try:fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise DemoError('Another command is using this session') from None
        try:yield
        finally:fcntl.flock(stream,fcntl.LOCK_UN)


def compose(state,directory):
    return startup.compose_command(state['project'],directory/'synthetic.env',directory/'override.json')


def verify_resource(kind,identity,state):
    label_path='.Config.Labels' if kind=='container' else '.Labels'
    template='{{index '+label_path+' "com.docker.compose.project"}}|{{index '+label_path+' "'+LABEL+'"}}'
    actual=invoke(['docker',kind,'inspect','--format',template,identity],'Ownership check')
    require(actual==state['project']+'|'+state['guard'],'Resource ownership mismatch; refusing action')
    if kind=='volume':
        require(identity in {state['project']+'_'+name for name in ('mysql_data','redis_data','rabbitmq_data')},'Unexpected demo volume')
    if kind=='network':
        name=invoke(['docker','network','inspect','--format','{{.Name}}',identity],'Network name check')
        require(name==state['project']+'_resq_net','Unexpected demo network')


def inventory(state):return deployment.resources(state['project'],env())


def require_fresh_names(state):
    require(not any(inventory(state).values()),'Refusing an already existing demo project')
    names=[('volume',state['project']+'_'+name) for name in ('mysql_data','redis_data','rabbitmq_data')]
    names.append(('network',state['project']+'_resq_net'))
    for kind,name in names:
        result=subprocess.run(['docker',kind,'inspect',name],env=env(),capture_output=True)
        require(result.returncode!=0,'Refusing an existing resource name, including unlabelled resources')


def verify_all(state):
    require(state['project']=='resq-demo-'+state['guard'][:12] and re.fullmatch(r'[a-f0-9]{32}',state['guard']),'Refusing original deployment')
    resources=inventory(state)
    for kind,identities in resources.items():
        for identity in identities:verify_resource(kind,identity,state)
    return resources


def container(state,directory,service):
    require(service in SERVICES,'Unexpected service')
    identity=invoke(compose(state,directory)+['ps','-a','-q',service],'Service identity lookup')
    require(bool(re.fullmatch(r'[a-f0-9]{64}',identity)),'Expected one exact container ID')
    verify_resource('container',identity,state)
    expected=state.get('containers',{}).get(service)
    if expected:require(identity==expected,'Container was replaced; prepare a new session')
    return identity


def sql(state,directory,statement):
    return seeds.execute_sql(container(state,directory,'mysql'),statement,env())


def redis(state,directory,*args):
    require(args and args[0] in ('EXISTS','GET','ZRANGE','ZCARD','ZRANK'),'Only scoped Redis read probes are allowed')
    require(len(args)>=2 and re.fullmatch(r'(resource:\d+(?::slots:\d{4}-\d{2}-\d{2})?|resq:waitlist:slot:\d+)',args[1]),'Unexpected Redis key')
    return invoke(['docker','exec',container(state,directory,'redis'),'redis-cli','--raw',*args],'Scoped Redis probe')


def http(state,method,path,expected=200,payload=None,token=None,request_id=None):
    try:status,body=request_json('http://127.0.0.1:'+str(state['ports']['RESQ_APP_PORT']),method,path,payload,token,request_id)
    except TransportError as error:raise DemoError(str(error)) from None
    if expected is not None:require(status==expected,method+' '+path+' returned HTTP '+str(status))
    return (status,body) if expected is None else body


def broker(state,directory,method,path,payload=None):
    container(state,directory,'rabbitmq')
    credentials=json.loads((directory/'credentials.json').read_text())
    auth=base64.b64encode((credentials['brokerUser']+':'+credentials['brokerPassword']).encode()).decode()
    headers={'Authorization':'Basic '+auth,'Content-Type':'application/json'}
    data=None if payload is None else json.dumps(payload).encode()
    req=urllib.request.Request('http://127.0.0.1:'+str(state['ports']['RESQ_RABBITMQ_MANAGEMENT_PORT'])+path,data=data,headers=headers,method=method)
    try:
        with open_direct(req,timeout=10) as response:return json.loads(response.read())
    except (OSError,ValueError):raise DemoError('Isolated broker API operation failed') from None


def wait_for(predicate,message,timeout=15):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if predicate():return
        time.sleep(0.5)
    raise DemoError(message)


def register(state,role):
    credentials={'username':'demo_'+state['guard'][:12]+'_'+role,'password':secrets.token_urlsafe(18)}
    result=http(state,'POST','/api/auth/register',201,{**credentials,'email':credentials['username']+'@example.invalid'})
    credentials['userId']=int(result['userId'])
    return credentials


def login(state,credentials):return http(state,'POST','/api/auth/login',payload={k:credentials[k] for k in ('username','password')})['token']


def slot(state,role):
    choices=[x for x in state['seed']['slots'] if x['scenario']==role]
    require(len(choices)==1,'Missing scenario slot');return choices[0]


def new_request():return 'demo-'+secrets.token_hex(16)


def reserve(state,slot_id,token,key=None):
    return http(state,'POST','/api/reservations',201,{'slotId':int(slot_id)},token,key or new_request())


def check_reservation(state,directory,identity,status):
    require(sql(state,directory,'SELECT status FROM reservation WHERE id='+str(int(identity))+';')==status,'Unexpected reservation database state')


def check_slot(state,directory,identity,status):
    require(sql(state,directory,'SELECT status FROM resource_slot WHERE id='+str(int(identity))+';')==status,'Unexpected slot database state')


def safe_check(entry,label,**evidence):
    entry['checks'].append({'name':label,**evidence});print('[PASS] '+label,flush=True)


def save_report(state,directory):
    # Deliberate allowlist: never serialize credentials or arbitrary response bodies/errors.
    value={k:state[k] for k in ('version','project','guard','createdAtUtc','commit','jarSha256','timeoutSeconds','ports','ready','modules')}
    value['seed']=state.get('seed');value['failureRoutingEvidence']=state.get('failureRoutingEvidence')
    value['runtimePreservation']=state.get('runtimePreservation')
    value['prepareDurationSeconds']=state.get('prepareDurationSeconds')
    value['cleaned']=state.get('cleaned',False)
    write_json(directory/'report.json',value)
    lines=['# ResQ v1.0 演示报告','', '项目：`'+state['project']+'`','',
           '状态：'+('环境就绪' if state['ready'] else '环境未就绪/已清理'),'']
    for name,result in state['modules'].items():
        lines+=['## '+name,'','结果：'+result['status']+'；耗时 '+str(result.get('durationSeconds',0))+' 秒','']
        lines+=['- PASS: '+item['name']+' '+json.dumps({k:v for k,v in item.items() if k!='name'},ensure_ascii=False) for item in result['checks']]
        if 'failure' in result:lines+=['- FAIL: '+result['failure']]
        lines+=['']
    lines+=['候补仅入队、去重、排名；通知仅日志模拟。并发数字不是容量指标；故障路由使用专用隔离测试证据。','']
    (directory/'report.md').write_text('\n'.join(lines));os.chmod(directory/'report.md',0o600)


def preserve_check(state):
    after=deployment.existing_snapshot(env());before=state['existingRuntime']
    result={'existingContainersUnchanged':all(after['containers'].get(k)==v for k,v in before['containers'].items()),
            'existingVolumesPreserved':set(before['volumes']).issubset(after['volumes'])}
    require(all(result.values()),'Pre-existing runtime changed; inspect preservation summary')
    return result


def cleanup(state,directory):
    require(not state.get('cleaned',False),'Session is already cleaned')
    verify_all(state) # Validate EVERY resource before any mutation.
    invoke(compose(state,directory)+['down','--volumes','--remove-orphans','--timeout','20'],'Owned demo cleanup')
    require(not any(inventory(state).values()),'Owned resources remain after cleanup')
    probe=subprocess.run(['docker','image','inspect','--format','{{index .Config.Labels "'+LABEL+'"}}',state['image']],env=env(),capture_output=True,text=True)
    if probe.returncode==0:
        require(probe.stdout.strip()==state['guard'],'Image ownership mismatch; image retained')
        invoke(['docker','image','rm',state['image']],'Owned demo image cleanup')
    state['ready']=False;state['cleaned']=True;state['phase']='cleaned';state['runtimePreservation']=preserve_check(state)
    write_json(directory/'state.json',state);save_report(state,directory)
    # Retain the reports/state, erase this session's generated credentials after successful cleanup.
    for name in ('credentials.json','synthetic.env','override.json'):
        (directory/name).unlink(missing_ok=True)
    print('[PASS] 仅清理本 session 的容器、网络、数据卷和镜像；原环境保留。',flush=True)
