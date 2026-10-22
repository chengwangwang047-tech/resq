import pathlib,json,subprocess,datetime
root=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96')
s=json.loads((root/'lab-public.json').read_text())
def cmd(a):
 p=subprocess.run(a,capture_output=True,text=True); return {'exit':p.returncode,'stdout':p.stdout,'stderr':p.stderr}
e={'observed_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'rabbit_connections':cmd(['rabbitmqctl','list_connections','vhost','state','--quiet']),
 'mysql_connections':cmd(['mysql','-NBe',"SELECT DB,COMMAND,COUNT(*) FROM information_schema.PROCESSLIST WHERE DB LIKE 'd24cache_%' GROUP BY DB,COMMAND;"]),
 'redis_db15':cmd(['redis-cli','-n','15','DBSIZE']),
 'redis_original_db0_fixture_resource_absent':cmd(['redis-cli','-n','0','EXISTS','resource:24000001']),
 'redis_clients':cmd(['redis-cli','CLIENT','LIST']),
 'mq_original':cmd(['rabbitmqctl','list_queues','-p','/','name','messages_ready','messages_unacknowledged','consumers','--quiet']),
 'mq_lab':cmd(['rabbitmqctl','list_queues','-p',s['schema'],'name','messages_ready','messages_unacknowledged','consumers','--quiet'])}
# Client list contains network endpoints and library names, never values or credentials.
(root/'isolation-evidence.json').write_text(json.dumps(e,indent=2))
print(json.dumps({k:v for k,v in e.items() if k not in ['redis_clients']},indent=2))
