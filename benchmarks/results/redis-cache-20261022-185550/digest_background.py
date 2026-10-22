import subprocess,pathlib,json,datetime
out=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96')
q="SHOW GLOBAL STATUS LIKE 'Performance_schema_digest_lost'; SELECT COUNT_STAR,SUM_ROWS_SENT,SUM_ROWS_EXAMINED FROM performance_schema.events_statements_summary_by_digest WHERE DIGEST IS NULL; SELECT @@performance_schema_digests_size;"
p=subprocess.run(['mysql','--batch','--raw','-e',q],capture_output=True,text=True)
(out/'digest-background.tsv').write_text('observed_utc='+datetime.datetime.now(datetime.timezone.utc).isoformat()+'\n'+p.stdout+p.stderr)
print('digest diagnostic saved',p.returncode)
