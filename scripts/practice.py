#!/usr/bin/env python3
"""Start/stop the locally provisioned persistent practice stack. Never deletes volumes."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

import start_compose as startup
import verify_compose_deployment as deployment

ROOT=Path.home()/'.local/share/resq/practice'
LABEL='resq.practice.guard'


class PracticeError(RuntimeError):pass


def require(condition,message):
    if not condition:raise PracticeError(message)


def environment():
    value=deployment.clean_environment();value['MAVEN_SKIP_RC']='true'
    return value


def command(args,label):
    try:return deployment.run(args,environment(),label)
    except RuntimeError as error:raise PracticeError(str(error)) from None


def load():
    require(not ROOT.is_symlink(),'Practice root must not be a symlink')
    require((ROOT/'current.json').is_file(),'This computer has no provisioned practice config; see docs/practice.md')
    metadata=json.loads((ROOT/'current.json').read_text())
    guard=metadata['guard']
    require(bool(re.fullmatch(r'[a-f0-9]{32}',guard)),'Invalid practice identity')
    require(metadata['project']=='deploy' and metadata['volumeProject']=='resq-demo-'+guard[:12],'Invalid practice project')
    directory=ROOT/guard
    require(not directory.is_symlink(),'Practice directory must not be a symlink')
    for filename,key in (('synthetic.env','envHash'),('override.json','overrideHash')):
        require(hashlib.sha256((directory/filename).read_bytes()).hexdigest()==metadata[key],'Practice config changed; review before startup')
    for role,name in metadata['volumes'].items():
        require(role in ('mysql_data','redis_data','rabbitmq_data') and name==metadata['volumeProject']+'_'+role,'Unexpected data volume')
        labels=command(['docker','volume','inspect','--format',
            '{{index .Labels "com.docker.compose.project"}}|{{index .Labels "resq.demo.guard"}}',name],'Practice volume ownership')
        require(labels==metadata['volumeProject']+'|'+guard,'Practice volume ownership mismatch')
    require(len(metadata['volumes'])==3,'All three practice volumes are required')
    return metadata,directory


def compose(metadata,directory):return startup.compose_command('deploy',directory/'synthetic.env',directory/'override.json')


def validate_existing(metadata):
    identities=command(['docker','ps','-aq','--no-trunc','--filter','label=com.docker.compose.project=deploy'],'Practice container inventory').splitlines()
    for identity in identities:
        label=command(['docker','inspect','--format','{{index .Config.Labels "'+LABEL+'"}}',identity],'Practice container ownership')
        require(label==metadata['guard'],'Current deploy containers belong to another environment; refusing changes')


def validate_model(metadata,directory):
    model=json.loads(command(compose(metadata,directory)+['config','--format','json'],'Private practice config'))
    require(set(model['services'])=={'app','mysql','redis','rabbitmq'},'Unexpected practice services')
    for role,name in metadata['volumes'].items():
        require(model['volumes'][role]['external'] is True and model['volumes'][role]['name']==name,'Practice data volume mapping changed')
    app=model['services']['app']['environment']
    require(app['SPRING_PROFILES_ACTIVE']=='prod' and app['RESQ_PROD_DB_URL'].startswith('jdbc:mysql://mysql:3306/resq?'),'Unexpected practice database connection')
    require(app['RESQ_PROD_REDIS_HOST']=='redis' and app['RESQ_PROD_RABBITMQ_HOST']=='rabbitmq','Unexpected practice dependency connection')
    for service in model['services'].values():
        require(service['labels'][LABEL]==metadata['guard'],'Missing practice container guard')
    return model


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('start','stop','status'))
    parser.add_argument('--build',action='store_true',help='With start only; build the packaged application JAR into practice-latest')
    args=parser.parse_args()
    require(not args.build or args.action=='start','--build is only valid with start')
    metadata,directory=load();validate_model(metadata,directory);validate_existing(metadata)
    cmd=compose(metadata,directory)
    if args.action=='start':startup.start(cmd,environment(),build=args.build)
    elif args.action=='stop':
        command(cmd+['stop'],'Practice stop')
        print('练习环境已停止，三个练习数据卷和原数据卷均保留。')
    else:
        for service in ('app','mysql','redis','rabbitmq'):
            identity=command(cmd+['ps','-a','-q',service],'Practice service lookup')
            if not identity:print(service+': not created');continue
            status=command(['docker','inspect','--format','{{.State.Status}} | {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}',identity],'Practice service state')
            print(service+': '+status)
        print('HTTP http://127.0.0.1:8081/actuator/health')


if __name__=='__main__':
    try:main()
    except Exception as error:
        # Never print raw subprocess exceptions, private configs or credentials.
        message=str(error) if isinstance(error,(PracticeError,startup.DeploymentError)) else type(error).__name__
        print('Practice operation refused/failed: '+message+'. No volumes were removed.',file=sys.stderr)
        sys.exit(1)
