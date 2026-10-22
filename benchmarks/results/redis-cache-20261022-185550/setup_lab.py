"""Run as WSL root. Create a retained, isolated benchmark lab; never clean business data."""
import pathlib, subprocess, os, json, secrets, datetime, hashlib, shutil, pwd
root=pathlib.Path('/home/colaminos/resq'); os.chdir(root)
run_id='d24cache_'+datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y%m%d_%H%M%S')+'_'+secrets.token_hex(3)
out=root/'benchmarks/results'/run_id; out.mkdir(parents=True)
priv=pathlib.Path('/tmp')/(run_id+'_private'); priv.mkdir(mode=0o700)
schema=run_id; db_user='d24c_'+secrets.token_hex(4); db_pw=secrets.token_urlsafe(30)
mq_user='d24c_'+secrets.token_hex(4); mq_pw=secrets.token_urlsafe(30)
def cmd(a,**kw): return subprocess.run(a,check=True,capture_output=True,text=True,**kw).stdout
def mysql(sql): return cmd(['mysql','--batch','--raw','--skip-column-names'],input=sql)
def save(n,x): (out/n).write_text(json.dumps(x,indent=2,ensure_ascii=False) if not isinstance(x,str) else x)
def integrity():
 result={}
 for p in root.rglob('*'):
  if p.is_file() and not any(x in p.relative_to(root).parts for x in ['.git','benchmarks']):
   result[str(p.relative_to(root))]=hashlib.sha256(p.read_bytes()).hexdigest()
 return result
save('project-before-hashes.json',integrity())
save('git-before.txt',cmd(['git','-c','safe.directory='+str(root),'status','--short']))
tables=mysql('SHOW TABLES FROM resq').splitlines()
def business_hash():
 return {t:hashlib.sha256(mysql('SELECT * FROM resq.`'+t+'` ORDER BY 1').encode()).hexdigest() for t in tables}
save('business-before-hashes.json',business_hash())
save('business-before-counts.tsv',mysql(';'.join('SELECT '+"'"+t+"'"+',COUNT(*) FROM resq.`'+t+'`' for t in tables)))
save('mq-before.tsv',cmd(['rabbitmqctl','list_queues','-p','/','name','messages_ready','messages_unacknowledged','consumers','--quiet']))
assert cmd(['redis-cli','-n','15','DBSIZE']).strip()=='0','Redis DB15 no longer empty'
save('redis-initial.txt',cmd(['redis-cli','-n','15','DBSIZE']))
ddl='CREATE DATABASE `'+schema+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\nUSE `'+schema+'`;\n'
for t in tables:
 raw=mysql('SHOW CREATE TABLE resq.`'+t+'`').split('\t',1)[1].strip()
 # --raw preserves physical newlines in CREATE TABLE.
 ddl+=raw+';\n'
save('schema-copy.sql',ddl)
mysql(ddl)
mysql("CREATE USER '"+db_user+"'@'localhost' IDENTIFIED BY '"+db_pw+"'; GRANT SELECT,INSERT,UPDATE,DELETE ON `"+schema+"`.* TO '"+db_user+"'@'localhost'; GRANT SELECT ON performance_schema.* TO '"+db_user+"'@'localhost';")
cmd(['rabbitmqctl','add_vhost',schema]); cmd(['rabbitmqctl','add_user',mq_user,mq_pw]); cmd(['rabbitmqctl','set_permissions','-p',schema,mq_user,'.*','.*','.*'])
settings={'run_id':run_id,'root':str(root),'out':str(out),'schema':schema,'db_user':db_user,'db_password':db_pw,'mq_user':mq_user,'mq_password':mq_pw,'redis_db':15,'resource_id':24000001,'port':8080}
(priv/'settings.json').write_text(json.dumps(settings)); (priv/'settings.json').chmod(0o600)
save('lab-public.json',{k:v for k,v in settings.items() if not any(w in k for w in ['password','user'])})
save('preflight-original-data.tsv',mysql("SELECT NOW(3),UTC_TIMESTAMP(3); SELECT id,name,type,status FROM resq.compute_resource WHERE id IN (111,112); SELECT * FROM resq.resource_slot WHERE resource_id IN (111,112); SELECT id,slot_id,status,expire_at FROM resq.reservation WHERE status='PENDING' AND expire_at<=NOW();"))
uid=pwd.getpwnam('colaminos').pw_uid; gid=pwd.getpwnam('colaminos').pw_gid
for p in [priv,*priv.rglob('*'),out,*out.rglob('*')]: os.chown(p,uid,gid)
print(json.dumps({'run_id':run_id,'out':str(out),'private_settings':str(priv/'settings.json'),'tables':tables,'business_db_untouched':True}))
