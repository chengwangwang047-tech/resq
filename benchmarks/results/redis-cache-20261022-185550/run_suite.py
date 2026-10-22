import sys,pathlib,json
root=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96')
sys.path.insert(0,str(root))
import cache_lab
plan=[(50,'cold',1),(50,'hot',1),(50,'hot',2),(50,'cold',2),
      (100,'hot',1),(100,'cold',1),(100,'cold',2),(100,'hot',2),
      (200,'cold',1),(200,'hot',1),(200,'hot',2),(200,'cold',2)]
cache_lab.save('formal-plan.json',{'order':plan,'vus':[50,100,200],'iterations_per_vu':30,'sleep_seconds':1,'first_block_already_executed':True})
first=json.loads((root/'50vu-r1-cold.evidence.json').read_text())
assert first['k6_exit']==0 and first['slot_select_delta']==1500
for n,arm,rep in plan[1:]: cache_lab.block(n,arm,rep)
print('ALL FORMAL BLOCKS COMPLETE',flush=True)
