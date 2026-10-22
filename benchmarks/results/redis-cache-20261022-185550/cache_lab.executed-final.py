"""Independent benchmark orchestration; application sources and configuration are read only."""
import os, pathlib, json, subprocess, sys, time, datetime, signal, hashlib, shutil, socket, concurrent.futures, re, threading, collections
import xml.etree.ElementTree as ET
import requests
SETTINGS=pathlib.Path('/tmp/d24cache_20261022_185550_9afb96_private/settings.json')
S=json.loads(SETTINGS.read_text()); ROOT=pathlib.Path(S['root']); OUT=pathlib.Path(S['out']); os.chdir(ROOT)
SESSION=requests.Session(); SESSION.trust_env=False
def save(name,data): (OUT/name).write_text(data if isinstance(data,str) else json.dumps(data,indent=2,ensure_ascii=False))
def run(args,**kw): return subprocess.run(args,capture_output=True,text=True,**kw)
def redis(*args):
 p=run(['redis-cli','-n',str(S['redis_db']),'--raw',*map(str,args)]); assert p.returncode==0,p.stderr; return p.stdout
def db(sql,readonly=True):
 env=dict(os.environ,MYSQL_PWD=S['db_password'])
 if readonly: sql='SET SESSION TRANSACTION READ ONLY; START TRANSACTION;\n'+sql+'\nCOMMIT;'
 p=run(['mysql','-h','127.0.0.1','-u',S['db_user'],'-D',S['schema'],'--batch','--raw','--skip-column-names'],input=sql,env=env)
 assert p.returncode==0,p.stderr
 return p.stdout
def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def url(date=None): return f'http://127.0.0.1:{S["port"]}/api/resources/{S["resource_id"]}'+('/slots?date='+date if date else '')
def get(date=None):
 r=SESSION.get(url(date),timeout=15); r.raise_for_status(); return r
def key(date): return f'resource:{S["resource_id"]}:slots:{date}'
def manifest(): return json.loads((OUT/'fixtures-manifest.json').read_text())
def public_env(): return {'RESQ_DB_URL':f'jdbc:mysql://127.0.0.1:3306/{S["schema"]}?characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai','SPRING_RABBITMQ_VIRTUAL_HOST':S['schema'],'SPRING_DATA_REDIS_DATABASE':str(S['redis_db']),'SPRING_APPLICATION_JSON':json.dumps({'logging':{'level':{'root':'INFO','com.resq.cache':'INFO','com.resq.service.ResourceService':'INFO'}}})}
def build_start():
 with socket.socket() as sock:
  sock.settimeout(0.5); assert sock.connect_ex(('127.0.0.1',8080))!=0,'8080 already in use'
 build=OUT/'build'; build.mkdir()
 tree=ET.parse(ROOT/'pom.xml'); ns={'m':'http://maven.apache.org/POM/4.0.0'}; tag=lambda x:'{'+ns['m']+'}'+x
 ET.register_namespace('',ns['m']); project=tree.getroot(); b=project.find('m:build',ns)
 for name,value in [('directory',str(build/'target')),('sourceDirectory',str(ROOT/'src/main/java'))]: ET.SubElement(b,tag(name)).text=value
 resources=ET.SubElement(b,tag('resources')); resource=ET.SubElement(resources,tag('resource')); ET.SubElement(resource,tag('directory')).text=str(ROOT/'src/main/resources')
 tree.write(build/'pom.xml',encoding='utf-8',xml_declaration=True)
 p=run(['mvn','-q','-f',str(build/'pom.xml'),'-Dmaven.test.skip=true','package'])
 save('build.log',p.stdout+p.stderr); assert p.returncode==0,'build failed; inspect build.log'
 jar=build/'target/resq-0.0.1-SNAPSHOT.jar'; assert jar.exists()
 env=dict(os.environ,**public_env(),RESQ_DB_USERNAME=S['db_user'],RESQ_DB_PASSWORD=S['db_password'],RESQ_RABBITMQ_USERNAME=S['mq_user'],RESQ_RABBITMQ_PASSWORD=S['mq_password'])
 assert env.get('RESQ_JWT_SECRET_BASE64'),'original JWT environment missing'
 app_log=open(OUT/'application.log','w')
 p=subprocess.Popen(['java','-Xms512m','-Xmx512m','-jar',str(jar)],stdout=app_log,stderr=subprocess.STDOUT,env=env,start_new_session=True)
 life={'pid':p.pid,'start':now(),'jar':str(jar),'jvm_flags':['-Xms512m','-Xmx512m'],'overrides':public_env(),'original_app_listening':False,'service_dependencies_isolated':True}
 save('application-lifecycle.json',life)
 for _ in range(90):
  if p.poll() is not None: raise RuntimeError('application exited; inspect local log')
  try:
   r=SESSION.get('http://127.0.0.1:8080/api/resources',timeout=1)
   if r.status_code==200: break
  except requests.RequestException: pass
  time.sleep(1)
 else: raise RuntimeError('application not ready')
 life['ready']=now(); save('application-lifecycle.json',life)
 save('runtime.txt',run(['java','-version']).stderr+run(['k6','version']).stdout+run(['uname','-a']).stdout+run(['lscpu']).stdout+run(['free','-m']).stdout)
 print('built and ready',p.pid,flush=True)
def fixtures():
 assert db('SELECT COUNT(*) FROM compute_resource;').strip()=='0'
 start=datetime.date(2080,1,1); dates=[(start+datetime.timedelta(days=i)).isoformat() for i in range(6120)]
 name='DAY24_CACHE_ONLY_'+S['run_id']; rid=S['resource_id']
 sql=f"START TRANSACTION;\nINSERT INTO compute_resource(id,name,type,status,created_at) VALUES ({rid},'{name}','GPU','AVAILABLE',NOW());\n"
 values=[f"({rid},'{d} {h:02d}:00:00','{d} {h+1:02d}:00:00','AVAILABLE')" for d in dates for h in [9,10]]
 sql+='INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) VALUES\n'+',\n'.join(values)+';\nCOMMIT;\n'
 save('fixture-inserts.sql',sql); save('fixture-inserts.tsv',db(sql,False))
 rows=db('SELECT DATE(slot_start),id FROM resource_slot ORDER BY slot_start,id;').splitlines(); mapped={}
 for row in rows:
  d,i=row.split('\t'); mapped.setdefault(d,[]).append(int(i))
 items=[{'date':d,'slotIds':mapped[d]} for d in dates]
 m={'resource_id':rid,'resource_name':name,'dates':items[:6000],'aux_dates':items[6000:],'rows_per_date':2}
 save('fixtures-manifest.json',m)
 save('db-fixtures.tsv',db('SELECT id,name,type,status FROM compute_resource; SELECT resource_id,COUNT(*),MIN(slot_start),MAX(slot_start) FROM resource_slot GROUP BY resource_id; SELECT COUNT(*) FROM reservation; SELECT COUNT(*) FROM user;'))
 save('db-explain.tsv',db(f"EXPLAIN SELECT id,resource_id,slot_start,slot_end,status FROM resource_slot WHERE resource_id={rid} AND slot_start>='2080-01-01' AND slot_start<'2080-01-02' ORDER BY slot_start,id;"))
 get(); print('fixtures created',len(items)*2,'slots',flush=True)
def metrics():
 q="SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_SENT,SUM_ROWS_EXAMINED FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='"+S['schema']+"' AND DIGEST_TEXT LIKE 'SELECT%FROM%resource_slot%' AND DIGEST_TEXT LIKE '%slot_start%' AND DIGEST_TEXT LIKE '%ORDER BY%';"
 rows=db(q).splitlines(); result={}
 for r in rows:
  d,text,count,timer,sent,exam=r.split('\t'); result[d]={'digest_text':text,'count':int(count),'sum_timer_ps':int(timer),'rows_sent':int(sent),'rows_examined':int(exam)}
 return result
def connection_selects():
 # Per-thread counters avoid a shared digest counter updated by every JDBC connection.
 rows=db("SELECT t.THREAD_ID,e.COUNT_STAR,t.PROCESSLIST_ID,e.SUM_ROWS_SENT,e.SUM_ROWS_EXAMINED FROM performance_schema.threads t JOIN performance_schema.events_statements_summary_by_thread_by_event_name e ON e.THREAD_ID=t.THREAD_ID WHERE t.PROCESSLIST_DB='"+S['schema']+"' AND t.PROCESSLIST_ID<>CONNECTION_ID() AND e.EVENT_NAME='statement/sql/select';").splitlines()
 threads={r.split('\t')[0]:int(r.split('\t')[1]) for r in rows}
 details={r.split('\t')[0]:dict(zip(['connection_id','rows_sent','rows_examined'],map(int,r.split('\t')[2:]))) for r in rows}
 q="SELECT DIGEST_TEXT,COUNT_STAR FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='"+S['schema']+"' AND DIGEST_TEXT LIKE 'SELECT%' AND (DIGEST_TEXT LIKE '%FROM `reservation`%' OR DIGEST_TEXT LIKE '%FROM `compute_resource`%' OR DIGEST_TEXT LIKE '%FROM `resource_slot`%');"
 digest={r.rsplit('\t',1)[0]:int(r.rsplit('\t',1)[1]) for r in db(q).splitlines()}
 scans=sum(v for k,v in digest.items() if 'FROM `reservation`' in k and '`expire_at`' in k and 'LIMIT' in k)
 return {'threads':threads,'thread_details':details,'background_due_scan_count':scans,'business_select_digests':digest}
class Monitor:
 def __init__(self,label):
  self.p=subprocess.Popen(['redis-cli','--raw','MONITOR'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
  self.ready=threading.Event(); self.events=[]; self.label=label
  self.thread=threading.Thread(target=self.read,daemon=True); self.thread.start()
  assert self.ready.wait(3),'Redis monitor not ready'
 def read(self):
  pattern=re.compile(r'^(\d+\.\d+) \[15 [^\]]+\] "(GET|SET)" "(resource:24000001(?::slots:\d{4}-\d{2}-\d{2})?)"')
  for line in self.p.stdout:
   if line.strip()=='OK': self.ready.set(); continue
   m=pattern.match(line)
   if m: self.events.append({'redis_epoch':m[1],'command':m[2],'key':m[3]})
   # All unselected commands and all values are discarded without persistence.
 def stop(self):
  time.sleep(0.1); self.p.terminate(); self.thread.join(3)
  (OUT/(self.label+'.redis-owned-commands.ndjson')).write_text(''.join(json.dumps(e)+'\n' for e in self.events))
  count=collections.Counter((e['command'],'slot' if ':slots:' in e['key'] else 'resource') for e in self.events)
  summary={f'{k[0]}_{k[1]}':v for k,v in count.items()}
  summary['slot_unique_get_keys']=len({e['key'] for e in self.events if e['command']=='GET' and ':slots:' in e['key']})
  save(self.label+'.redis-owned-commands-summary.json',summary); return summary
def snapkeys(dates):
 keys=[key(x['date']) for x in dates]
 # One Lua request atomically reads only manifest target keys; no data, locks or auth inspected.
 lua="local n=0;local lo=999999999;local hi=-3;for _,k in ipairs(KEYS) do if redis.call('EXISTS',k)==1 then n=n+1;local t=redis.call('PTTL',k);if t<lo then lo=t end;if t>hi then hi=t end end end;return {#KEYS,n,lo,hi}"
 return dict(zip(['target_keys','present','min_pttl_ms','max_pttl_ms'],map(int,redis('EVAL',lua,len(keys),*keys).splitlines())))
def clear(dates,label):
 # Only exact experiment slot keys, guarded by private lab settings and DB ownership marker.
 assert db(f"SELECT name FROM compute_resource WHERE id={S['resource_id']};").strip()=='DAY24_CACHE_ONLY_'+S['run_id']
 keys=[key(x['date']) for x in dates]
 assert all(re.fullmatch(r'resource:24000001:slots:\d{4}-\d{2}-\d{2}',k) for k in keys)
 removed=0
 for i in range(0,len(keys),500): removed+=int(redis('DEL',*keys[i:i+500]).strip())
 save(label+'.exact-key-deletion.json',{'redis_db':S['redis_db'],'count':removed,'keys':keys,'scope':'only owned manifest slot keys; resource cache retained'})
def warm(dates):
 get()
 def one(x):
  with requests.Session() as sess:
   sess.trust_env=False
   r=sess.get(url(x['date']),timeout=20); r.raise_for_status(); a=r.json(); assert [s['id'] for s in a]==x['slotIds']; return len(r.content)
 with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex: sizes=list(ex.map(one,dates))
 return {'n':len(sizes),'bytes_min':min(sizes),'bytes_max':max(sizes)}
def pilot():
 dates=manifest()['aux_dates'][:10]; clear(dates,'pilot')
 before=metrics(); ks0=snapkeys(dates); cold=[]
 get()
 for x in dates:
  t=time.perf_counter(); r=get(x['date']); cold.append({'date':x['date'],'ms':(time.perf_counter()-t)*1000,'bytes':len(r.content),'body':r.json()})
 middle=metrics(); ks1=snapkeys(dates); hot=[]
 for x in dates:
  t=time.perf_counter(); r=get(x['date']); hot.append({'date':x['date'],'ms':(time.perf_counter()-t)*1000,'bytes':len(r.content),'body':r.json()})
 after=metrics(); save('pilot.json',{'before':before,'after_cold':middle,'after_hot':after,'keys_before':ks0,'keys_after_cold':ks1,'cold':cold,'hot':hot})
 assert sum(v['count'] for v in middle.values())-sum(v['count'] for v in before.values())==10,'pilot cold DB count mismatch'
 assert sum(v['count'] for v in after.values())==sum(v['count'] for v in middle.values()),'pilot hot accessed DB'
 assert ks0['present']==0 and ks1['present']==10
 assert [x['body'] for x in cold]==[x['body'] for x in hot]
 # Nonempty rows, IDs, dates and status checked in low-intensity k6 test too.
 small=OUT/'pilot-manifest.json'; small.write_text(json.dumps({'dates':dates}))
 env={k:v for k,v in os.environ.items() if not any(s in k for s in ['PASSWORD','SECRET','TOKEN'])}
 env.update(MANIFEST=str(small),VUS='2',ITERATIONS='5',RESOURCE_ID=str(S['resource_id']),ARM='pilot-hot')
 p=run(['k6','run','--summary-export='+str(OUT/'pilot.summary.json'),str(OUT/'cache_compare.js')],env=env)
 save('pilot.k6.log',p.stdout+p.stderr); assert p.returncode==0,'low-intensity k6 failed'
 print('pilot passed: 10 cold SELECTs, zero hot SELECTs, 2-VU k6 checks passed',flush=True)
def block(n,arm,rep,iterations=30):
 label=f'{n}vu-r{rep}-{arm}'; dates=manifest()['dates'][:n*iterations]
 assert not (OUT/(label+'.evidence.json')).exists(),'refuse to overwrite existing evidence; choose new repeat identifier'
 if arm=='hot': prep=warm(dates)
 else: clear(dates,label); get(); prep={'cold_unique_keys':len(dates)}
 ks0=snapkeys(dates); assert ks0['present']==(len(dates) if arm=='hot' else 0)
 if arm=='hot': assert ks0['min_pttl_ms']>60000,'hot keys TTL insufficient'
 # Refresh prerequisite resource cache via its normal endpoint; both arms keep it hot.
 assert db(f"SELECT name FROM compute_resource WHERE id={S['resource_id']};").strip()=='DAY24_CACHE_ONLY_'+S['run_id']
 resource_key=f'resource:{S["resource_id"]}'
 save(label+'.resource-key-prep.json',{'exact_owned_key':resource_key,'redis_db':S['redis_db'],'removed':int(redis('DEL',resource_key)),'purpose':'normal API refill before both arms, outside timing'})
 get(); resource_ttl=int(redis('PTTL',resource_key))
 assert resource_ttl>120000,'resource TTL too low for full block'
 start_db=metrics(); save(label+'.db-before.json',start_db)
 conn0=connection_selects(); save(label+'.connections-before.json',conn0)
 stats0=redis('INFO','stats'); save(label+'.redis-stats-before.txt',stats0)
 instrumented=os.environ.get('RESQ_CACHE_MONITOR','1')!='0'
 monitor=Monitor(label) if instrumented else None
 start=now(); mf=OUT/(label+'.manifest.json'); mf.write_text(json.dumps({'dates':dates}))
 env={k:v for k,v in os.environ.items() if not any(s in k for s in ['PASSWORD','SECRET','TOKEN'])}
 env.update(MANIFEST=str(mf),VUS=str(n),ITERATIONS=str(iterations),RESOURCE_ID=str(S['resource_id']),ARM=arm)
 cmd=['k6','run','--summary-mode=full','--summary-trend-stats=avg,min,med,max,p(90),p(95),p(99)','--summary-export='+str(OUT/(label+'.summary.json')),'--out','json='+str(OUT/(label+'.metrics.ndjson')),str(OUT/'cache_compare.js')]
 save(label+'.command.json',{'command':cmd,'public_env':{k:env[k] for k in ['MANIFEST','VUS','ITERATIONS','RESOURCE_ID','ARM']}})
 with (OUT/(label+'.k6.log')).open('w') as f: p=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,env=env)
 observed=monitor.stop() if monitor else {'monitor_enabled':False}
 end=now(); conn1=connection_selects(); save(label+'.connections-after.json',conn1)
 end_db=metrics(); save(label+'.db-after.json',end_db); save(label+'.redis-stats-after.txt',redis('INFO','stats'))
 ks1=snapkeys(dates); db_count=sum(v['count'] for v in end_db.values())-sum(v['count'] for v in start_db.values())
 assert set(conn0['threads'])==set(conn1['threads']),'JDBC connections changed; counter comparison invalid'
 per_connection_delta=sum(conn1['threads'][k]-v for k,v in conn0['threads'].items())
 scan_delta=conn1['background_due_scan_count']-conn0['background_due_scan_count']
 exact_slot_count=per_connection_delta-scan_delta
 rows_delta=sum(conn1['thread_details'][k]['rows_sent']-v['rows_sent'] for k,v in conn0['thread_details'].items())
 state={'label':label,'vus':n,'arm':arm,'repeat':rep,'start':start,'end':end,'k6_exit':p.returncode,'iterations_per_vu':iterations,'prep':prep,'keys_before':ks0,'keys_after':ks1,'resource_pttl_before_ms':resource_ttl,'slot_select_delta':db_count,'expected_slot_selects':len(dates) if arm=='cold' else 0,'redis_observed':observed}
 state.update(connection_select_delta=per_connection_delta,background_scan_delta=scan_delta,slot_selects_via_connections=exact_slot_count,shared_digest_difference=db_count-exact_slot_count)
 state.update(connection_rows_sent_delta=rows_delta)
 save(label+'.evidence.json',state)
 print(label,'exit',p.returncode,'DB SELECT',db_count,'keys',ks0['present'],'->',ks1['present'],flush=True)
 assert p.returncode==0,'k6 failure; keep evidence and stop'
 assert exact_slot_count==state['expected_slot_selects'],'per-connection DB evidence mismatch; stop'
 assert rows_delta==2*state['expected_slot_selects'],'DB rows-sent evidence mismatch; stop'
 assert ks1['present']==len(dates),'keys missing after run'
 if instrumented:
  assert observed.get('GET_slot',0)==len(dates) and observed['slot_unique_get_keys']==len(dates),'slot GET count/uniqueness mismatch'
  assert observed.get('SET_slot',0)==(len(dates) if arm=='cold' else 0),'slot SET evidence mismatch'
  assert observed.get('GET_resource',0)==len(dates) and observed.get('SET_resource',0)==0,'resource cache not uniformly hot'
 return state
def warm_jit():
 # Auxiliary dates are never formal target keys. Same JVM handles all formal arms.
 ds=manifest()['aux_dates'][20:120]; warm(ds)
 for _ in range(20): warm(ds)
 save('jvm-warmup.json',{'finished':now(),'requests':2100,'dates':100,'concurrency':16,'excluded_from_formal_results':True})
 print('JIT auxiliary warmup complete',flush=True)
def stop():
 life=json.loads((OUT/'application-lifecycle.json').read_text()); pid=life['pid']
 cmdline=pathlib.Path(f'/proc/{pid}/cmdline').read_bytes()
 assert life['jar'].encode() in cmdline,'PID ownership changed'
 os.killpg(pid,signal.SIGTERM); life['stop_requested']=now()
 for _ in range(30):
  s=socket.socket(); s.settimeout(0.5); listening=s.connect_ex(('127.0.0.1',8080))==0; s.close()
  if not listening: break
  time.sleep(1)
 life['stopped_observed']=now(); life['port8080_listening']=listening; save('application-lifecycle.json',life)
 assert not listening
 print('self-created application stopped',flush=True)
if __name__=='__main__':
 action=sys.argv[1]
 if action=='start': build_start()
 elif action=='fixtures': fixtures()
 elif action=='pilot': pilot()
 elif action=='warmup': warm_jit()
 elif action=='block': block(int(sys.argv[2]),sys.argv[3],int(sys.argv[4]))
 elif action=='stop': stop()
