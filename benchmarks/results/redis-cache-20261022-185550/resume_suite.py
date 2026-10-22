import pathlib,sys,json
root=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96'); sys.path.insert(0,str(root)); import cache_lab
for arm in ['hot','cold']:
 e=json.loads((root/f'50vu-r4-{arm}.evidence.json').read_text()); assert e['slot_selects_via_connections']==(1500 if arm=='cold' else 0)
plan=[(100,'hot',1),(100,'cold',1),(100,'cold',2),(100,'hot',2),(200,'cold',1),(200,'hot',1),(200,'hot',2),(200,'cold',2)]
cache_lab.save('formal-plan-revised.json',{'accepted_50_order':[[50,'cold',1],[50,'hot',1],[50,'hot',4],[50,'cold',4]],'remaining_order':plan,'excluded_blocks':['50vu-r2-hot','50vu-r2-cold'],'exclusion_reason':'r2 cold shared digest count mismatch; paired hot kept as diagnostic; raw files retained','row_counter_added_from_100vu':True})
for n,arm,rep in plan: cache_lab.block(n,arm,rep)
print('REVISED FORMAL SUITE COMPLETE',flush=True)
