import pathlib,zipfile,hashlib,json,shutil,os
root=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96')
dest=pathlib.Path('/mnt/c/Users/colaminos/Documents/Codex/2026-10-22/referenced-chatgpt-conversation-this-is-an-3/outputs'); dest.mkdir(exist_ok=True)
s=json.loads(pathlib.Path('/tmp/d24cache_20261022_185550_9afb96_private/settings.json').read_text())
private_values=[s['db_password'].encode(),s['mq_password'].encode()]
for env_name in ['RESQ_JWT_SECRET_BASE64','RESQ_DB_PASSWORD','RESQ_RABBITMQ_PASSWORD']:
 if os.environ.get(env_name): private_values.append(os.environ[env_name].encode())
files=[p for p in root.rglob('*') if p.is_file() and p.name not in ['privacy-check.json','artifact-sha256.json'] and not ('build' in p.relative_to(root).parts and 'target' in p.relative_to(root).parts)]
found=[]
for p in files:
 carry=b''
 with p.open('rb') as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):
   data=carry+chunk
   if any(v and v in data for v in private_values): found.append(str(p.relative_to(root)))
   carry=data[-1024:]
assert not found,'private values detected; do not package'
(root/'privacy-check.json').write_text(json.dumps({'files_scanned':len(files),'actual_private_values_found':False,'private_settings_excluded':True,'build_binary_outputs_excluded':True},indent=2))
files.append(root/'privacy-check.json')
manifest={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
(root/'artifact-sha256.json').write_text(json.dumps(manifest,indent=2)); files.append(root/'artifact-sha256.json')
archive=dest/'Day24-Redis-cache-evidence.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for p in files: z.write(p,arcname=root.name+'/'+str(p.relative_to(root)))
with zipfile.ZipFile(archive) as z:
 assert z.testzip() is None and len(z.namelist())==len(set(z.namelist())),'archive integrity failure'
shutil.copy2(root/'day24-redis-cache-report.md',dest/'Day24-Redis-cache-report.md')
shutil.copy2(root/'day24-redis-cache-conclusion.md',dest/'Day24-Redis-cache-conclusion.md')
shutil.copy2(root/'summary-per-block.csv',dest/'Day24-Redis-cache-results.csv')
print(json.dumps({'archive_bytes':archive.stat().st_size,'files':len(files),'report':str(dest/'Day24-Redis-cache-report.md')},ensure_ascii=False))
