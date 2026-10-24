#!/usr/bin/env python3
"""Run reproducible HTTP demonstrations in a prepared, owned ResQ demo session."""
import argparse
import collections
import concurrent.futures
import json
import os
import subprocess
import sys
import threading
import time
import urllib.parse
import common as c


def basic(state,directory,entry):
    c.http(state,'GET','/api/reservations/me',401)
    a=c.register(state,'basic_a');b=c.register(state,'basic_b')
    token=c.login(state,a);c.login(state,b)
    c.safe_check(entry,'真实 HTTP 注册、登录与匿名鉴权通过',registeredUsers=2)
    resource=c.http(state,'POST','/api/resources',201,{'name':'Live HTTP Demo '+state['guard'][:8],'type':'GPU'},token)
    resources=c.http(state,'GET','/api/resources')
    c.require(any(int(r['id'])==int(resource['id']) for r in resources),'Created resource missing from list')
    for role in ('basic-confirm','basic-cancel'):
        slot=c.slot(state,role);path='/api/resources/'+str(slot['resourceId'])+'/slots?date='+slot['slotStart'][:10]
        c.require(any(int(s['id'])==int(slot['id']) and s['status']=='AVAILABLE' for s in c.http(state,'GET',path)),'Seed slot missing from HTTP query')
    c.safe_check(entry,'资源创建、列表与未来时隙 HTTP 查询通过',createdResourceId=resource['id'])
    slot=c.slot(state,'basic-confirm');r=c.reserve(state,slot['id'],token)
    c.require(r['status']=='PENDING','Expected PENDING')
    result=c.http(state,'POST','/api/reservations/'+str(r['id'])+'/confirm',token=token)
    c.require(result['status']=='CONFIRMED','Confirmation failed')
    c.check_reservation(state,directory,r['id'],'CONFIRMED');c.check_slot(state,directory,slot['id'],'RESERVED')
    c.safe_check(entry,'第一条预约确认：HTTP 与数据库一致',reservationId=r['id'])
    slot=c.slot(state,'basic-cancel');r=c.reserve(state,slot['id'],token)
    path='/api/reservations/'+str(r['id'])
    c.require(c.http(state,'DELETE',path,token=token)['status']=='CANCELLED','Cancellation failed')
    c.http(state,'DELETE',path,409,token=token)
    c.check_reservation(state,directory,r['id'],'CANCELLED');c.check_slot(state,directory,slot['id'],'AVAILABLE')
    c.safe_check(entry,'另一条 PENDING 取消并释放，重复取消返回 409',reservationId=r['id'])


def concurrency(state,directory,entry):
    entry['stage']='pool-login'
    credentials=json.loads((directory/'credentials.json').read_text())['pool']
    c.require(len(credentials)==50,'Expected 50 distinct prepared users')
    tokens=[c.login(state,user) for user in credentials]
    entry['stage']='competing-requests'
    print('50 个独立用户已登录，开始同步竞争同一时隙。',flush=True)
    target=c.slot(state,'concurrency');c.check_slot(state,directory,target['id'],'AVAILABLE')
    barrier=threading.Barrier(50)
    def attempt(pair):
        number,token=pair;barrier.wait(timeout=20)
        status,body=c.http(state,'POST','/api/reservations',None,{'slotId':target['id']},token,c.new_request())
        return number,status,body
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:responses=list(pool.map(attempt,enumerate(tokens)))
    counts=collections.Counter(status for _,status,_ in responses)
    c.require(counts[201]==1 and counts[429]==0,'Competition requires exactly one success and zero rate-limit responses')
    c.require(set(counts)<={201,409,503},'Unexpected technical failure during competition')
    for _,status,body in responses:
        if status==409:c.require(body.get('message')=='时隙不可用，预约失败','409 is not the expected inventory conflict')
    number,_,winner=next(r for r in responses if r[1]==201)
    effective=c.sql(state,directory,"SELECT COUNT(*) FROM reservation WHERE slot_id="+str(int(target['id']))+" AND status IN ('PENDING','CONFIRMED');")
    c.require(effective=='1','Database effective reservation count is not one')
    c.check_slot(state,directory,target['id'],'RESERVED')
    c.http(state,'POST','/api/reservations/'+str(winner['id'])+'/confirm',token=tokens[number])
    c.check_reservation(state,directory,winner['id'],'CONFIRMED')
    c.safe_check(entry,'50 个不同用户竞争：成功 1，限流 0，数据库有效预约 1',httpCounts={str(k):v for k,v in sorted(counts.items())},reservationId=winner['id'])
    entry['stage']='request-idempotency'
    user=c.register(state,'idem');other=c.register(state,'idem_other');token=c.login(state,user);other_token=c.login(state,other)
    target=c.slot(state,'idempotency');key=c.new_request()
    first=c.reserve(state,target['id'],token,key)
    for _ in range(2):c.require(c.reserve(state,target['id'],token,key)['id']==first['id'],'Replay returned a different reservation')
    c.http(state,'POST','/api/reservations',409,{'slotId':c.slot(state,'idempotency-conflict')['id']},token,key)
    c.http(state,'POST','/api/reservations',409,{'slotId':target['id']},other_token,key)
    c.require(c.sql(state,directory,"SELECT COUNT(*) FROM reservation WHERE request_id='"+key+"';")=='1','Request ID created multiple rows')
    c.http(state,'POST','/api/reservations/'+str(first['id'])+'/confirm',token=token)
    c.safe_check(entry,'请求重放返回同一预约；更换时隙或用户复用 ID 返回 409',reservationId=first['id'],databaseRows=1)


def advanced(state,directory,entry):
    c.require(time.time()-state['createdAtEpoch']<3600,'Prepare a fresh session (under one hour) for MQ attribution')
    ttl=state['timeoutSeconds'];queue='resq.reservation.timeout.delay.queue.'+str(ttl)+'s'
    info=c.broker(state,directory,'GET','/api/queues/%2F/'+urllib.parse.quote(queue,safe=''))
    c.require(info['arguments']['x-message-ttl']==ttl*1000,'Waiting queue TTL does not match confirmation deadline')
    user=c.register(state,'advanced');token=c.login(state,user)
    target=c.slot(state,'timeout');r=c.reserve(state,target['id'],token)
    deadline=c.sql(state,directory,'SELECT TIMESTAMPDIFF(SECOND,created_at,expire_at) FROM reservation WHERE id='+str(int(r['id']))+';')
    c.require(int(deadline)==ttl,'Database confirmation deadline is inconsistent')
    c.safe_check(entry,'确认期限与等待队列 TTL 一致，扫描补偿延后 24 小时',timeoutSeconds=ttl,queueTtlMillis=ttl*1000)
    print('等待真实延迟消息释放预约，最多 '+str(ttl+45)+' 秒；不改写数据库时间。',flush=True)
    event='reservation-expire-'+str(int(r['id']))
    record="SELECT COUNT(*) FROM mq_consume_record WHERE event_id='"+event+"';"
    def expired():
        row=c.sql(state,directory,'SELECT status FROM reservation WHERE id='+str(int(r['id']))+';')
        return row=='EXPIRED' and c.sql(state,directory,record)=='1'
    c.wait_for(expired,'MQ-driven expiration did not complete',ttl+45)
    c.check_slot(state,directory,target['id'],'AVAILABLE')
    c.safe_check(entry,'真实 MQ 消费：PENDING → EXPIRED、时隙释放、消费记录 1',reservationId=r['id'],eventId=event)
    newer=c.reserve(state,target['id'],token)
    c.http(state,'POST','/api/reservations/'+str(newer['id'])+'/confirm',token=token)
    app=c.container(state,directory,'app')
    def duplicate_count():
        logs=c.invoke(['docker','logs','--tail','10000',app],'Synthetic demo log probe')
        return sum('[DAY21 DUPLICATE] eventId='+event in line and line.split('eventId=',1)[-1].strip()==event for line in logs.splitlines())
    before=duplicate_count()
    payload={'properties':{'content_type':'application/json','delivery_mode':2},'routing_key':'reservation.timeout.expired',
        'payload':json.dumps({'eventId':event,'reservationId':r['id'],'eventType':'RESERVATION_EXPIRE'}),'payload_encoding':'string'}
    for _ in range(2):
        result=c.broker(state,directory,'POST','/api/exchanges/%2F/resq.reservation.timeout.dead.exchange/publish',payload)
        c.require(result['routed'] is True,'Duplicate event was not routed')
    c.wait_for(lambda:duplicate_count()>=before+2,'Duplicate deliveries were not observed',20)
    def drained():
        data=c.broker(state,directory,'GET','/api/queues/%2F/resq.reservation.timeout.dead.queue')
        return data.get('messages_ready',1)==0 and data.get('messages_unacknowledged',1)==0
    c.wait_for(drained,'Timeout consumer queue did not drain',20)
    c.require(c.sql(state,directory,record)=='1','Duplicate event created extra consumption records')
    c.check_reservation(state,directory,r['id'],'EXPIRED');c.check_reservation(state,directory,newer['id'],'CONFIRMED')
    c.check_slot(state,directory,target['id'],'RESERVED')
    failed=c.broker(state,directory,'GET','/api/queues/%2F/resq.reservation.timeout.failed.queue')
    c.require(failed.get('messages',0)==0,'Unexpected failed messages in the main demo broker')
    c.safe_check(entry,'同 eventId 重投 2 次：真实数据库去重、正常消费、不释放后续预约',consumptionRows=1,duplicateDeliveries=2,newReservationId=newer['id'])
    evidence=state['failureRoutingEvidence'];c.require(evidence['suite']['skipped']=='0' and evidence['suite']['failures']=='0' and evidence['suite']['errors']=='0','Dedicated fault evidence did not pass')
    c.safe_check(entry,'独立故障测试证据：异常/非法 JSON 重试 3 次进入失败队列，成功消息确认',source=evidence['mode'],tests=1,scope='separate disposable broker; mocked service')
    cache=c.slot(state,'cache');date=cache['slotStart'][:10]
    key='resource:'+str(cache['resourceId'])+':slots:'+date
    path='/api/resources/'+str(cache['resourceId'])+'/slots?date='+date
    c.require(c.redis(state,directory,'EXISTS',key)=='0','Cache scenario must start cold')
    def assert_cache(status):
        rows=c.http(state,'GET',path)
        c.require(any(int(x['id'])==int(cache['id']) and x['status']==status for x in rows),'HTTP cached slot content is wrong')
        stored=json.loads(c.redis(state,directory,'GET',key))
        c.require(any(int(x['id'])==int(cache['id']) and x['status']==status for x in stored),'Redis slot content is wrong')
        c.check_slot(state,directory,cache['id'],status)
    assert_cache('AVAILABLE')
    reservation=c.reserve(state,cache['id'],token)
    c.wait_for(lambda:c.redis(state,directory,'EXISTS',key)=='0','Committed reservation did not evict cache')
    assert_cache('RESERVED')
    c.http(state,'DELETE','/api/reservations/'+str(reservation['id']),token=token)
    c.wait_for(lambda:c.redis(state,directory,'EXISTS',key)=='0','Cancellation did not evict cache')
    assert_cache('AVAILABLE')
    c.safe_check(entry,'缓存冷建、预约后失效重建、取消后再次失效重建；HTTP/Redis/MySQL 一致',cacheKey=key)
    wait=c.slot(state,'waitlist');holder=c.reserve(state,wait['id'],token)
    c.http(state,'POST','/api/reservations/'+str(holder['id'])+'/confirm',token=token)
    one=c.register(state,'wait1');two=c.register(state,'wait2');first=c.login(state,one);second=c.login(state,two)
    path='/api/slots/'+str(wait['id'])+'/waitlist'
    c.require(c.http(state,'POST',path,token=first)['position']==1,'First waitlist rank is wrong')
    c.require(c.http(state,'POST',path,token=second)['position']==2,'Second waitlist rank is wrong')
    c.http(state,'POST',path,token=first)
    for token_value,rank in ((first,1),(second,2)):
        result=c.http(state,'GET',path,token=token_value)
        c.require(result['joined'] and result['position']==rank and result['total']==2,'Waitlist duplicate changed rank or size')
    redis_key='resq:waitlist:slot:'+str(wait['id'])
    c.require(c.redis(state,directory,'ZCARD',redis_key)=='2','Redis waitlist size is not two')
    c.safe_check(entry,'两用户候补排名 1/2，重复入队不改变排名或规模',members=2,automaticPromotionImplemented=False)


MODULES={'basic':basic,'concurrency':concurrency,'advanced':advanced}


def run_modules(state,directory,names):
    c.require(state['ready'] and not state.get('cleaned'),'Session is not ready')
    c.verify_all(state)
    for service in c.SERVICES:
        identity=c.container(state,directory,service)
        health=c.invoke(['docker','inspect','--format','{{.State.Health.Status}}',identity],'Service health')
        c.require(health=='healthy','Demo service is not healthy: '+service)
    for name in names:
        c.require(name not in state['modules'],'Scenario already attempted; prepare a NEW session for repeat demonstrations')
    for name in names:
        entry={'status':'RUNNING','checks':[]};state['modules'][name]=entry
        c.write_json(directory/'state.json',state);started=time.monotonic()
        print('\n=== '+name+' ===',flush=True)
        try:
            MODULES[name](state,directory,entry);entry['status']='PASSED'
            state['runtimePreservation']=c.preserve_check(state)
        except BaseException as error:
            entry['status']='FAILED';entry['failure']=str(error) if isinstance(error,c.DemoError) else type(error).__name__
            raise
        finally:
            entry['durationSeconds']=round(time.monotonic()-started,2)
            c.write_json(directory/'state.json',state);c.save_report(state,directory)
    print('\nRESULT: ALL PASSED',flush=True)
    print('REPORT '+str(directory/'report.md'),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['all',*MODULES,'status','cleanup'])
    parser.add_argument('--session',help='Defaults to the most recently prepared session')
    args=parser.parse_args();state,directory=c.load(args.session)
    with c.locked(directory):
        if args.command=='cleanup':c.cleanup(state,directory)
        elif args.command=='status':
            runtime=[];http_health=None
            if not state.get('cleaned'):
                resources=c.verify_all(state)
                for identity in resources['container']:
                    line=c.invoke(['docker','inspect','--format',
                        '{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}',identity],'Runtime status')
                    service,status,health=line.split('|')
                    runtime.append({'service':service,'status':status,'health':health})
                if state['ready']:http_health=c.http(state,'GET','/actuator/health').get('status')
            print(json.dumps({'session':state['guard'],'project':state['project'],'phase':state['phase'],
                'ready':state['ready'],'cleaned':state.get('cleaned',False),
                'runtime':runtime,
                'httpHealth':http_health,
                'modules':{k:v['status'] for k,v in state['modules'].items()},
                'report':str(directory/'report.md')},ensure_ascii=False,indent=2))
        else:run_modules(state,directory,list(MODULES) if args.command=='all' else [args.command])


if __name__=='__main__':
    os.umask(0o077)
    try:main()
    except Exception as error:
        print('DEMO FAILED: '+(str(error) if isinstance(error,c.DemoError) else type(error).__name__)+
              '；现场保留，不自动重跑写请求。',file=sys.stderr)
        sys.exit(1)
