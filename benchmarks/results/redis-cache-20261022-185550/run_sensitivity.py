import pathlib,sys,os,json
root=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96'); sys.path.insert(0,str(root)); import cache_lab
for n,reps in [(50,[1,4]),(100,[1,2]),(200,[1,2])]:
 for rep in reps:
  for arm in ['cold','hot']:
   e=json.loads((root/f'{n}vu-r{rep}-{arm}.evidence.json').read_text())
   assert e['k6_exit']==0
os.environ['RESQ_CACHE_MONITOR']='0'
cache_lab.save('sensitivity-plan.json',{'order':[[200,'cold',3],[200,'hot',3]],'monitor_enabled':False,'purpose':'check observer sensitivity on same JVM; kept separate from main results'})
for arm in ['cold','hot']: cache_lab.block(200,arm,3)
print('SENSITIVITY COMPLETE',flush=True)
