import pathlib,json,hashlib,subprocess,os
root=pathlib.Path('/home/colaminos/resq'); out=root/'benchmarks/results/d24cache_20261022_185550_9afb96'; target=out/'source-audit'; target.mkdir(exist_ok=True)
files=['src/main/java/com/resq/cache/ResourceCache.java','src/main/java/com/resq/cache/ResourceSlotCache.java','src/main/java/com/resq/service/ResourceService.java','src/main/java/com/resq/mapper/JdbcResourceSlotMapper.java','src/main/java/com/resq/controller/ResourceController.java','src/main/java/com/resq/scheduler/ReservationExpirationScheduler.java','src/main/java/com/resq/config/RabbitMQConfig.java','src/main/java/com/resq/config/ReservationTimeoutMqConfig.java']
hashes={}
for f in files:
 p=root/f; data=p.read_bytes(); hashes[f]=hashlib.sha256(data).hexdigest(); (target/p.name).write_bytes(data)
properties=(root/'src/main/resources/application.properties').read_text()
filtered='\n'.join(f'{i}: {l}' for i,l in enumerate(properties.splitlines(),1) if any(x in l for x in ['logging.level.','spring.sql.init.mode','server.port','spring.data.redis.','spring.rabbitmq.host','spring.rabbitmq.port']))
(target/'runtime-config-safe-excerpt.txt').write_text(filtered)
(target/'source-hashes.json').write_text(json.dumps(hashes,indent=2))
log=(out/'application.log').read_text(); private=json.loads(pathlib.Path('/tmp/d24cache_20261022_185550_9afb96_private/settings.json').read_text())
secrets=[v for k,v in private.items() if 'password' in k]+[os.environ.get('RESQ_JWT_SECRET_BASE64','')]
(out/'logging-verification.json').write_text(json.dumps({'debug_lines':sum(' DEBUG ' in l for l in log.splitlines()),'cache_debug_lines':sum(any(s in l for s in ['[CACHE HIT]','[SLOTS CACHE HIT]','[SLOTS DB QUERY]']) for l in log.splitlines()),'log_contains_private_values':any(s and s in log for s in secrets),'explicit_logging_settings':filtered},indent=2))
print('source audit captured; app DEBUG lines',sum(' DEBUG ' in l for l in log.splitlines()))
