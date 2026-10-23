#!/usr/bin/env python3
"""Create private MySQL/Redis processes; run a test whitelist; always remove own data.

No live credentials, Spring Boot context, docker, sudo, production reset or Git mutation.
Requires Ubuntu mysqld/mysql/redis-server/redis-cli, Java 21 and Maven already installed.
"""
from pathlib import Path
from contextlib import contextmanager
import datetime, hashlib, json, os, shutil, signal, socket, subprocess, sys, tempfile, time, uuid
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[2]
STAMP = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
RESULTS = PROJECT / 'target' / 'day17-results' / (STAMP + '-' + uuid.uuid4().hex[:8])
TESTS = ','.join([
    'ReservationLockServiceTest', 'Day17ReservationHttpContractTest',
    'Day17LockTransactionTest', 'Day17ConcurrencyComparisonTest',
    'ReservationServiceTest', 'ResourceSlotCacheInvalidatorTest',
    'ResourceSlotCacheRetryTest', 'ResourceSlotCacheTest',
])

def private_environment():
    # Prevent inherited application/CLI/JVM credential settings from reaching logs or private tools.
    sensitive = {'MYSQL_PWD', 'REDISCLI_AUTH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
                 '_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS'}
    return {k: v for k, v in os.environ.items()
            if not k.startswith(('RESQ_', 'SPRING_')) and k not in sensitive}

def file_hashes():
    paths = list((PROJECT / 'src/main').rglob('*')) + [PROJECT / 'pom.xml']
    return {str(p.relative_to(PROJECT)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in paths if p.is_file()}

def check_ports():
    for port in (13317, 16317):
        with socket.socket() as sock:
            try:
                sock.bind(('127.0.0.1', port))
            except OSError:
                raise RuntimeError(f'Dedicated test port {port} is already occupied; no process will be stopped')

def run(args, log=None, data=None, timeout=60):
    result = subprocess.run(args, input=data, stdout=log or subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=timeout,
                            env=private_environment())
    if result.returncode:
        raise RuntimeError(f'{Path(args[0]).name} failed; see private-instance logs in {RESULTS}')
    return result.stdout

def wait_ready(check, process, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Private instance exited during startup; inspect isolated logs')
        try:
            if check(): return
        except (OSError, subprocess.SubprocessError):
            pass
        time.sleep(.2)
    raise RuntimeError('Private instance startup timed out')

def stop_owned_process(process):
    """Stop only the process group created by this invocation, including Maven forks."""
    if process.poll() is None:
        try: os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError: pass
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait(timeout=5)
    # Maven's shell can exit before a forked JVM. Terminate its owned group as well.
    try: os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError: pass

def stop_owned_instances(instances, cleanup):
    for name, process in reversed(instances):
        stop_owned_process(process)
        cleanup[name + 'Stopped'] = process.poll() is not None
    instances.clear()

@contextmanager
def private_workspace(instances, cleanup):
    # No TemporaryDirectory finalizer: failed shutdown must retain the private data.
    directory = Path(tempfile.mkdtemp(prefix='resq-day17-', dir='/tmp')).resolve()
    try:
        yield directory
    finally:
        stop_owned_instances(instances, cleanup)
        # Verify the exact owned deletion target before a recursive removal.
        if directory.parent != Path('/tmp').resolve() or not directory.name.startswith('resq-day17-'):
            raise RuntimeError('Private workspace cleanup refused: unexpected path')
        shutil.rmtree(directory)
        cleanup['privateDataRemoved'] = not directory.exists()

def temporary_pom(destination):
    namespace = 'http://maven.apache.org/POM/4.0.0'
    ET.register_namespace('', namespace)
    tree = ET.parse(PROJECT / 'pom.xml')
    root = tree.getroot()
    q = lambda name: '{' + namespace + '}' + name
    build = root.find(q('build'))
    if build is None: build = ET.SubElement(root, q('build'))
    def set_path(name, value):
        element = build.find(q(name))
        if element is None: element = ET.SubElement(build, q(name))
        element.text = str(value)
    set_path('directory', destination.parent / 'build')
    set_path('sourceDirectory', PROJECT / 'src/main/java')
    set_path('testSourceDirectory', PROJECT / 'src/test/java')
    for tag, child, path in [('resources','resource','src/main/resources'),
                              ('testResources','testResource','src/test/resources')]:
        existing = build.find(q(tag))
        if existing is not None: build.remove(existing)
        parent = ET.SubElement(build, q(tag))
        res = ET.SubElement(parent, q(child))
        ET.SubElement(res, q('directory')).text = str(PROJECT / path)
    tree.write(destination, encoding='utf-8', xml_declaration=True)

def main():
    if sys.platform != 'linux': raise RuntimeError('Run inside WSL Ubuntu')
    for name in ['mysqld','mysql','redis-server','redis-cli','mvn','java']:
        if shutil.which(name) is None: raise RuntimeError('Missing required tool: ' + name)
    check_ports()
    RESULTS.mkdir(parents=True)
    protected = file_hashes()
    (RESULTS / 'main-and-pom-before.json').write_text(json.dumps(protected, indent=2))
    guard = uuid.uuid4().hex
    instances = []
    handles = []
    exit_code = 1
    cleanup = {'mysqlStopped': False, 'redisStopped': False, 'privateDataRemoved': False}
    temp_path = None
    try:
        with private_workspace(instances, cleanup) as directory:
            temp = Path(directory)
            temp_path = temp
            os.chmod(temp, 0o700)
            data = temp / 'mysql-data'
            data.mkdir()
            init_log = (RESULTS / 'mysql-init.log').open('w')
            handles.append(init_log)
            run(['mysqld','--no-defaults','--initialize-insecure',f'--datadir={data}',
                 '--innodb-buffer-pool-size=64M'], init_log, timeout=60)
            mysql_log = (RESULTS / 'mysql.log').open('w')
            handles.append(mysql_log)
            mysql = subprocess.Popen(['mysqld','--no-defaults',f'--datadir={data}',
                f'--socket={temp / "mysql.sock"}',f'--pid-file={temp / "mysql.pid"}',
                '--port=13317','--bind-address=127.0.0.1','--mysqlx=OFF',
                '--skip-log-bin','--innodb-buffer-pool-size=64M','--max-connections=64'],
                stdout=mysql_log, stderr=subprocess.STDOUT, start_new_session=True,
                env=private_environment())
            instances.append(('mysql', mysql))
            cli = ['mysql','--no-defaults','--protocol=SOCKET',f'--socket={temp / "mysql.sock"}',
                   '-u','root','--batch','--skip-column-names']
            wait_ready(lambda: subprocess.run(cli + ['-e','SELECT 1'],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2,
                        env=private_environment()).returncode == 0, mysql)
            sql = (PROJECT / 'src/test/resources/day17-schema.sql').read_text()
            setup = "CREATE DATABASE resq_day17 CHARACTER SET utf8mb4;\nUSE resq_day17;\n" + sql
            setup += "\nCREATE TABLE day17_safety_guard(token CHAR(32) NOT NULL);\n"
            setup += f"INSERT INTO day17_safety_guard VALUES('{guard}');\n"
            setup += "CREATE USER 'day17_test'@'127.0.0.1' IDENTIFIED BY '';\n"
            setup += "CREATE USER 'day17_test'@'localhost' IDENTIFIED BY '';\n"
            setup += "GRANT ALL ON resq_day17.* TO 'day17_test'@'127.0.0.1';\n"
            setup += "GRANT ALL ON resq_day17.* TO 'day17_test'@'localhost';\n"
            run(cli, data=setup)
            redis_log = (RESULTS / 'redis.log').open('w')
            handles.append(redis_log)
            redis = subprocess.Popen(['redis-server','--bind','127.0.0.1','--port','16317',
                '--save','','--appendonly','no','--dir',str(temp),'--daemonize','no'],
                stdout=redis_log, stderr=subprocess.STDOUT, start_new_session=True,
                env=private_environment())
            instances.append(('redis', redis))
            rcli = ['redis-cli','-h','127.0.0.1','-p','16317']
            wait_ready(lambda: subprocess.run(rcli + ['PING'], stdout=subprocess.PIPE,
                           stderr=subprocess.DEVNULL,text=True,timeout=2,
                           env=private_environment()).stdout.strip() == 'PONG', redis)
            run(rcli + ['SET','day17:safety-guard',guard])
            pom = temp / 'pom.xml'
            temporary_pom(pom)
            test_log = (RESULTS / 'maven.log').open('w')
            handles.append(test_log)
            print('Running isolated Day17 suite. Results:', RESULTS, flush=True)
            # Production runtime environment is explicitly removed. The test whitelist has no Boot context.
            environment = private_environment()
            args = ['mvn','-B','-f',str(pom),f'-Dtest={TESTS}',
                '-Dtest.groups=integration | !integration',
                '-Dresq.day17.integration=true',
                '-Dday17.mysql.url=jdbc:mysql://127.0.0.1:13317/resq_day17?characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai&connectTimeout=2000&socketTimeout=10000',
                '-Dday17.redis.port=16317',f'-Dday17.guard={guard}',f'-Dday17.results={RESULTS}',
                '-Dspring.sql.init.mode=never','test']
            proc = subprocess.Popen(args, env=environment, stdout=test_log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
            instances.append(('maven', proc))
            try:
                exit_code = proc.wait(timeout=360)
            except BaseException:
                stop_owned_process(proc)
                raise
            report_dir = temp / 'build/surefire-reports'
            if report_dir.exists(): shutil.copytree(report_dir, RESULTS / 'surefire-reports')
            final_sql = "USE resq_day17; SELECT 'reservation',COUNT(*) FROM reservation; SELECT 'resource_slot',COUNT(*) FROM resource_slot; SELECT 'compute_resource',COUNT(*) FROM compute_resource; SELECT 'user',COUNT(*) FROM `user`;"
            (RESULTS / 'private-final-db.txt').write_text(run(cli + ['-e',final_sql]))
            (RESULTS / 'private-final-redis.txt').write_text(run(rcli + ['--scan']))
            print('Maven exit:', exit_code, flush=True)
            # Stop only the owned process groups before private_workspace removes files.
            stop_owned_instances(instances, cleanup)
        cleanup['privateDataRemoved'] = not temp_path.exists()
    finally:
        stop_owned_instances(instances, cleanup)
        for handle in handles: handle.close()
        after = file_hashes()
        cleanup['mainAndPomUnchangedDuringRun'] = protected == after
        cleanup['privateDataRemoved'] = temp_path is not None and not temp_path.exists()
        (RESULTS / 'cleanup.json').write_text(json.dumps(cleanup, indent=2))
        if protected != after:
            print('Production files changed during run (possible user edit); acceptance needs recheck', file=sys.stderr)
            exit_code = 1
    return exit_code

if __name__ == '__main__':
    try: sys.exit(main())
    except Exception as error:
        print('Day17 isolated runner:', error, file=sys.stderr)
        sys.exit(1)
