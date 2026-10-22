"""WSL root, read-only verification of original business state after self-created app stops."""
import pathlib,json,subprocess,hashlib,datetime,os,time,socket
root=pathlib.Path('/home/colaminos/resq'); out=root/'benchmarks/results/d24cache_20261022_185550_9afb96'
s=json.loads((out/'lab-public.json').read_text())
def cmd(a):
 p=subprocess.run(a,capture_output=True,text=True); assert p.returncode==0,p.stderr; return p.stdout
def mysql(q): return cmd(['mysql','--batch','--raw','--skip-column-names','-e',q])
before=json.loads((out/'business-before-hashes.json').read_text())
after={t:hashlib.sha256(mysql('SELECT * FROM resq.`'+t+'` ORDER BY 1').encode()).hexdigest() for t in before}
(out/'business-after-hashes.json').write_text(json.dumps(after,indent=2))
project_before=json.loads((out/'project-before-hashes.json').read_text()); project_after={}
for p in root.rglob('*'):
 if p.is_file() and not any(x in p.relative_to(root).parts for x in ['.git','benchmarks']): project_after[str(p.relative_to(root))]=hashlib.sha256(p.read_bytes()).hexdigest()
changed=[k for k in sorted(set(project_before)|set(project_after)) if project_before.get(k)!=project_after.get(k)]
(out/'project-after-hashes.json').write_text(json.dumps(project_after,indent=2))
mq=cmd(['rabbitmqctl','list_queues','-p','/','name','messages_ready','messages_unacknowledged','consumers','--quiet']); (out/'mq-after.tsv').write_text(mq)
original_mq=(out/'mq-before.tsv').read_text(); mq_equal=sorted(mq.splitlines())==sorted(original_mq.splitlines())
labmq=cmd(['rabbitmqctl','list_queues','-p',s['schema'],'name','messages_ready','messages_unacknowledged','consumers','--quiet']); (out/'mq-lab-final.tsv').write_text(labmq)
connections=cmd(['rabbitmqctl','list_connections','vhost','state','--quiet']); (out/'rabbit-connections-final.tsv').write_text(connections)
life=json.loads((out/'application-lifecycle.json').read_text()); pid=life['pid']; stat=pathlib.Path(f'/proc/{pid}/stat'); state=stat.read_text().split()[2] if stat.exists() else 'absent'
sock=socket.socket(); sock.settimeout(.5); listening=sock.connect_ex(('127.0.0.1',8080))==0; sock.close()
git=cmd(['git','-c','safe.directory='+str(root),'-C',str(root),'status','--short']); (out/'git-final-status.txt').write_text(git)
diff=cmd(['git','-c','safe.directory='+str(root),'-C',str(root),'diff','--','.',':(exclude)benchmarks/**']); (out/'git-final-diff.txt').write_text(diff)
labdata=mysql('SELECT NOW(3),UTC_TIMESTAMP(3); SELECT COUNT(*) FROM `'+s['schema']+'`.reservation; SELECT COUNT(*) FROM `'+s['schema']+'`.user; SELECT COUNT(*),MIN(slot_start),MAX(slot_start),COUNT(DISTINCT DATE(slot_start)) FROM `'+s['schema']+'`.resource_slot; SELECT status,COUNT(*) FROM `'+s['schema']+'`.resource_slot GROUP BY status;')
(out/'lab-final-data.tsv').write_text(labdata)
e={'observed_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'original_business_table_hashes_equal':before==after,'business_table_equal':{t:before[t]==after[t] for t in before},'non_benchmarks_files_hashed':len(project_before),'non_benchmarks_changed_paths':changed,'git_tracked_diff_empty':not diff.strip(),'original_mq_queue_counts_consumers_equal':mq_equal,'app_pid_state':state,'app_pid_exited_or_reaped':state in ['absent','Z'],'port8080_listening':listening,'dedicated_rabbit_connection_absent':s['schema'] not in connections,'data_cleanup_performed':False,'lab_database_vhost_accounts_retained':True}
(out/'final-verification.json').write_text(json.dumps(e,indent=2))
assert before==after and not changed and not diff.strip() and mq_equal and e['app_pid_exited_or_reaped'] and not listening and e['dedicated_rabbit_connection_absent'],e
print(json.dumps(e,indent=2))
