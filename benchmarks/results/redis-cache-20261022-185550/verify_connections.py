import pathlib,json
root=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96'); results=[]
for p in sorted(root.glob('*.connections-before.json')):
 label=p.name.replace('.connections-before.json',''); afterpath=root/(label+'.connections-after.json')
 if not afterpath.exists(): continue
 before=json.loads(p.read_text()); after=json.loads(afterpath.read_text()); e=json.loads((root/(label+'.evidence.json')).read_text())
 changes={k:after['business_select_digests'].get(k,0)-v for k,v in before['business_select_digests'].items() if after['business_select_digests'].get(k,0)!=v}
 changes.update({k:v for k,v in after['business_select_digests'].items() if k not in before['business_select_digests']})
 allowed=lambda k: ('FROM `resource_slot`' in k and '`slot_start` >=' in k) or ('FROM `reservation`' in k and '`expire_at`' in k and 'LIMIT' in k)
 details=before.get('thread_details'); matching_conn=True if not details else all(after['thread_details'][k]['connection_id']==v['connection_id'] for k,v in details.items())
 expected=e['vus']*e['iterations_per_vu'] if e['arm']=='cold' else 0
 r={'label':label,'thread_ids_stable':set(before['threads'])==set(after['threads']),'connection_ids_stable':matching_conn,'connections':len(before['threads']), 'only_expected_business_sql_changed':all(allowed(k) for k in changes),'business_sql_digest_deltas':changes,'total_connection_SELECT_delta':e['connection_select_delta'],'background_scan_delta':e['background_scan_delta'],'derived_slot_SELECTs':e['slot_selects_via_connections'],'derived_matches_expected':e['slot_selects_via_connections']==expected,'rows_sent_collected':bool(details),'rows_sent_delta':e.get('connection_rows_sent_delta')}
 if details: r['rows_sent_match']=r['rows_sent_delta']==2*expected
 assert all(r[k] for k in ['thread_ids_stable','connection_ids_stable','only_expected_business_sql_changed','derived_matches_expected']) and r.get('rows_sent_match',True),r
 results.append(r)
(root/'connection-counter-verification.json').write_text(json.dumps({'blocks':results,'all_checks_passed':True},indent=2))
print('verified connection evidence blocks',len(results))
