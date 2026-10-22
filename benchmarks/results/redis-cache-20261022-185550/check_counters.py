import sys,pathlib,json
root=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96'); sys.path.insert(0,str(root)); import cache_lab as c
q="""SELECT name,enabled FROM performance_schema.setup_consumers WHERE name LIKE '%statement%';
SELECT THREAD_ID,PROCESSLIST_ID,PROCESSLIST_USER,PROCESSLIST_DB,PROCESSLIST_COMMAND FROM performance_schema.threads WHERE PROCESSLIST_DB='d24cache_20261022_185550_9afb96';
SELECT THREAD_ID,EVENT_NAME,COUNT_STAR FROM performance_schema.events_statements_summary_by_thread_by_event_name WHERE COUNT_STAR>0 AND EVENT_NAME LIKE 'statement/%' AND THREAD_ID IN (SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_DB='d24cache_20261022_185550_9afb96');
SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_ROWS_SENT,SUM_ROWS_EXAMINED FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='d24cache_20261022_185550_9afb96';
SELECT * FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA='d24cache_20261022_185550_9afb96';
SELECT * FROM performance_schema.events_statements_history_long WHERE CURRENT_SCHEMA='d24cache_20261022_185550_9afb96' LIMIT 3;
"""
c.save('counter-diagnostic.tsv',c.db(q)); print((root/'50vu-r2-cold.redis-owned-commands-summary.json').read_text()); print(c.db(q)[:15000])
