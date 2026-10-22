import pathlib,json,re,statistics,collections,csv,math,datetime
ROOT=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96')
def percentile(a,p):
 a=sorted(a); ix=(len(a)-1)*p; lo=math.floor(ix); hi=math.ceil(ix); return a[lo]+(a[hi]-a[lo])*(ix-lo)
def records(label):
 rows=[]
 if not (ROOT/(label+'.k6.log')).exists(): return rows
 for line in (ROOT/(label+'.k6.log')).read_text().splitlines():
  match=re.search(r'msg=("(?:[^"\\]|\\.)*")',line)
  if not match: continue
  try:
   msg=json.loads(match.group(1)); data=json.loads(msg)
   if data.get('kind')=='cache_request': rows.append(data)
  except (ValueError,TypeError): continue
 return rows
def summary(label):
 evidence=json.loads((ROOT/(label+'.evidence.json')).read_text())
 native=json.loads((ROOT/(label+'.summary.json')).read_text()); metrics=native['metrics']; rows=records(label)
 assert len(rows)==30*evidence['vus'],'missing request records'
 assert len({r['index'] for r in rows})==len(rows) and len({r['date'] for r in rows})==len(rows)
 assert all(r['status']==200 and r['valid'] for r in rows)
 assert metrics['http_reqs']['count']==len(rows)
 ds=[r['duration_ms'] for r in rows]
 assert abs(statistics.mean(ds)-metrics['http_req_duration']['avg'])<0.0001
 assert sum(' DEBUG ' in x for x in (ROOT/'application.log').read_text().splitlines())==0
 output={'label':label,'vus':evidence['vus'],'arm':evidence['arm'],'repeat':evidence['repeat'],'requests':len(rows),'actual_rps_k6':metrics['http_reqs']['rate'],
  'avg_ms':statistics.mean(ds),'median_ms':statistics.median(ds),'p95_ms':percentile(ds,.95),'p99_ms':percentile(ds,.99),'min_ms':min(ds),'max_ms':max(ds),
  'http_failure_rate':metrics['http_req_failed']['value'],'business_failure_rate':metrics['business_failure_rate']['value'],
  'db_slot_selects':evidence.get('slot_selects_via_connections',evidence['slot_select_delta']),'db_shared_digest_slot_selects':evidence['slot_select_delta'],'db_connection_selects':evidence.get('connection_select_delta','not collected'),'db_background_scans':evidence.get('background_scan_delta','not collected'),'db_rows_sent':evidence.get('connection_rows_sent_delta','not collected'),'redis_slot_GET':evidence['redis_observed'].get('GET_slot','not monitored'),'redis_slot_SET':evidence['redis_observed'].get('SET_slot','not monitored'),
  'monitor_enabled':evidence['redis_observed'].get('monitor_enabled',True),'resource_cache_misses_observed':evidence['redis_observed'].get('SET_resource','not monitored'),'bytes_min':min(r['bytes'] for r in rows),'bytes_max':max(r['bytes'] for r in rows),
  'start_utc':evidence['start'],'end_utc':evidence['end'],'k6_exit':evidence['k6_exit'],'hot_min_pttl_ms':evidence['keys_before']['min_pttl_ms'] if evidence['arm']=='hot' else '',
  'start_spread_ms':max(r['at_ms']-r['duration_ms'] for r in rows)-min(r['at_ms']-r['duration_ms'] for r in rows)}
 csvpath=ROOT/(label+'.requests.csv')
 with csvpath.open('w',newline='') as f:
  w=csv.DictWriter(f,fieldnames=list(rows[0])); w.writeheader(); w.writerows(sorted(rows,key=lambda r:r['index']))
 return output,ds,rows
all_rows=[]; pooled=collections.defaultdict(list); hists=[]; bins=[0,1,2,5,10,20,50,100,200,500,1000,float('inf')]
for path in sorted(ROOT.glob('*vu-r*-*.evidence.json')):
 label=path.name.replace('.evidence.json',''); item,ds,rows=summary(label); all_rows.append(item)
 item['accepted_main']=item['monitor_enabled'] and not (item['vus']==50 and item['repeat']==2)
 if item['accepted_main']: pooled[(item['vus'],item['arm'])].extend(ds)
 for lo,hi in zip(bins,bins[1:]): hists.append({'label':label,'lo_ms':lo,'hi_ms':hi,'count':sum(lo<=d<hi for d in ds)})
for n in [50,100,200]:
 for rep in [1,2,3,4]:
  if not all((ROOT/f'{n}vu-r{rep}-{arm}.evidence.json').exists() for arm in ['cold','hot']): continue
  c=records(f'{n}vu-r{rep}-cold'); h=records(f'{n}vu-r{rep}-hot');
  if not c or not h: continue
  assert sorted((r['index'],r['date'],r['bytes']) for r in c)==sorted((r['index'],r['date'],r['bytes']) for r in h),'payload mismatch'
if all_rows:
 with (ROOT/'summary-per-block.csv').open('w',newline='') as f:
  w=csv.DictWriter(f,fieldnames=list(all_rows[0])); w.writeheader(); w.writerows(all_rows)
 with (ROOT/'duration-histogram.csv').open('w',newline='') as f:
  w=csv.DictWriter(f,fieldnames=list(hists[0])); w.writeheader(); w.writerows(hists)
combined=[]
for (n,arm),ds in sorted(pooled.items()):
 subset=[r for r in all_rows if r['vus']==n and r['arm']==arm and r['accepted_main']]
 combined.append({'vus':n,'arm':arm,'blocks':len(subset),'requests':len(ds),'rps_range':[min(r['actual_rps_k6'] for r in subset),max(r['actual_rps_k6'] for r in subset)],'avg_ms':statistics.mean(ds),'median_ms':statistics.median(ds),'p95_ms':percentile(ds,.95),'p99_ms':percentile(ds,.99),'db_slot_selects':sum(r['db_slot_selects'] for r in subset),'http_failure_rate':0,'business_failure_rate':0})
(ROOT/'analysis.json').write_text(json.dumps({'blocks':all_rows,'pooled':combined,'percentile_method':'linear interpolation on all request duration samples; pooled values recomputed, not averaged percentiles'},indent=2))
print(json.dumps({'complete_blocks':len(all_rows),'pooled':combined},ensure_ascii=False,indent=2))
