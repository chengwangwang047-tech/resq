"""Reusable launcher after setup_lab.py creates NEW empty isolated lab settings.

Set RESQ_CACHE_SETTINGS to its private settings.json; copy cache_lab.py and
cache_compare.js into that settings out directory first. Never reuse a results
directory. This launcher refuses to start without explicit new settings.
"""
import os,sys,pathlib,json
assert os.environ.get('RESQ_CACHE_SETTINGS'),'set new private RESQ_CACHE_SETTINGS first'
s=json.loads(pathlib.Path(os.environ['RESQ_CACHE_SETTINGS']).read_text())
out=pathlib.Path(s['out']); assert not (out/'application-lifecycle.json').exists(),'require new lab/output directory'
sys.path.insert(0,str(out)); import cache_lab as c
try:
 c.build_start(); c.fixtures(); c.pilot(); c.warm_jit()
 for n in [50,100,200]:
  for arm,rep in [('cold',1),('hot',1),('hot',2),('cold',2)]: c.block(n,arm,rep)
 os.environ['RESQ_CACHE_MONITOR']='0'
 for arm in ['cold','hot']: c.block(200,arm,3)
finally:
 if (out/'application-lifecycle.json').exists(): c.stop()
