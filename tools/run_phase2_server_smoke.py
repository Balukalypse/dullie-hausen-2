"""Start only a prepared local testserver, inspect startup, send read-only commands and stop.
Run under the normal Windows user. Never prepares a world or accepts an EULA.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import uuid

TARGETS = {
    'net/minecraft/server/MinecraftServer': b'dulliehausen$getPlayerDataStorage',
    'net/minecraft/server/level/ServerPlayer': b'dulliehausen$drop',
    'net/minecraft/world/entity/item/ItemEntity': b'dulliehausen$pickup',
    'net/minecraft/world/inventory/AbstractContainerMenu': b'dulliehausen$menuDrop',
    'net/minecraft/world/inventory/AbstractCraftingMenu': b'dulliehausen$recipe',
    'net/minecraft/world/level/block/piston/PistonBaseBlock': b'erzmarken$denyOreMovement',
}


def validate(root):
    root = root.absolute()
    for path in (root, *root.parents):
        if path.is_symlink():
            raise ValueError('Verlinkter Testserverpfad')
    props = dict(line.split('=', 1) for line in (root / 'server.properties').read_text().splitlines()
                 if '=' in line and not line.lstrip().startswith('#'))
    expected = {'server-ip': '127.0.0.1', 'server-port': '25566', 'level-name': 'test-world',
                'online-mode': 'true', 'white-list': 'true', 'enforce-whitelist': 'true',
                'enable-rcon': 'false', 'enable-query': 'false'}
    if any(props.get(k) != v for k, v in expected.items()):
        raise ValueError('Testserver muss unveraendert lokal mit Anmeldung und Whitelist bleiben')
    if 'eula=true' not in (root / 'eula.txt').read_text().splitlines():
        raise ValueError('EULA nicht ausdruecklich akzeptiert')
    marker = json.loads((root / 'test-world/.dulliehausen-phase2-test.json').read_text())
    if (marker.get('schema') != 1 or marker.get('purpose') != 'isolated-phase2-test'
            or os.path.normcase(os.path.abspath(marker.get('worldPath', '')))
            != os.path.normcase(str(root / 'test-world'))):
        raise ValueError('Weltkennzeichen passt nicht zum Testserver')
    testers = marker.get('testers', [])
    if not 2 <= len(set(testers)) == len(testers) <= 8:
        raise ValueError('Ungeeignete Testkontenliste')
    if any(str(uuid.UUID(value)) != value for value in testers):
        raise ValueError('Ungueltige Testkonto-ID')
    whitelist = json.loads((root / 'whitelist.json').read_text())
    if len(whitelist) != len(testers) or {p['uuid'] for p in whitelist} != set(testers):
        raise ValueError('Whitelist passt nicht zum Weltkennzeichen')
    if int(props.get('max-players', '0')) != len(testers):
        raise ValueError('Spielerzahl passt nicht zu Testkonten')
    report = json.loads((root / 'phase2-build-report.json').read_text())
    jar = root / 'mods' / report['jar']
    if report.get('testBuild') is not True or hashlib.sha256(jar.read_bytes()).hexdigest() != report['sha256']:
        raise ValueError('Test-JAR stimmt nicht mit erfolgreichem Buildbericht ueberein')
    if len(list((root / 'mods').glob('*.jar'))) != 2:
        raise ValueError('Nur Test-Mod und Fabric-API duerfen installiert sein')
    if not (root / 'fabric-server-launch.jar').is_file():
        raise ValueError('Server-Launcher fehlt')
    return root


def run_process(command, root, logfile, timeout=600, stop_grace=30, echo=True):
    result = {'serverReady': False, 'testScopeConfirmed': False, 'stopRequested': False,
              'timedOut': False, 'forcedTermination': False, 'mixinErrors': [], 'exitCode': None,
              'monitorError': None, 'consoleMirrorWarnings': []}
    proc = subprocess.Popen(command, cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1)
    lock = threading.Lock()

    def send(commands):
        with lock:
            if proc.poll() is None and proc.stdin and not proc.stdin.closed:
                try:
                    proc.stdin.write(commands); proc.stdin.flush()
                    return True
                except (OSError, ValueError):
                    pass
        return False

    def read_output():
        try:
            with open(logfile, 'w', encoding='utf-8') as log:
                for line in proc.stdout:
                    log.write(line); log.flush()
                    if echo:
                        # Console encoding is a presentation concern, never a reason
                        # to abandon draining the child pipe (which can deadlock Java).
                        try:
                            print(line.rstrip(), flush=True)
                        except UnicodeError:
                            encoding = getattr(sys.stdout, 'encoding', None) or 'ascii'
                            safe = line.rstrip().encode(encoding, errors='backslashreplace').decode(encoding)
                            try:
                                print(safe, flush=True)
                            except (OSError, ValueError):
                                if len(result['consoleMirrorWarnings']) < 5:
                                    result['consoleMirrorWarnings'].append('Console mirror unavailable; file log continues')
                        except (OSError, ValueError):
                            if len(result['consoleMirrorWarnings']) < 5:
                                result['consoleMirrorWarnings'].append('Console mirror unavailable; file log continues')
                    if any(word in line for word in ('MixinTransformerError', 'InvalidMixinException',
                            'InjectionError', 'Critical injection failure', 'Mixin apply for mod erzmarken failed')):
                        result['mixinErrors'].append(line.strip())
                    if 'Isolierter Phase-2-Testbetrieb' in line:
                        result['testScopeConfirmed'] = True
                    if not result['serverReady'] and re.search(r'\bDone \([^)]+\)!', line):
                        result['serverReady'] = True
                        result['stopRequested'] = send('shop test status\nshop journal\nstop\n')
        except Exception as error:
            result['monitorError'] = repr(error)
            result['stopRequested'] = send('stop\n') or result['stopRequested']

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    try:
        try:
            result['exitCode'] = proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            result['timedOut'] = True
            result['stopRequested'] = send('stop\n') or result['stopRequested']
            try:
                result['exitCode'] = proc.wait(timeout=stop_grace)
            except subprocess.TimeoutExpired:
                result['forcedTermination'] = True
                proc.terminate()
                result['exitCode'] = proc.wait(timeout=15)
    finally:
        if proc.poll() is None:
            result['stopRequested'] = send('stop\n') or result['stopRequested']
            try:
                proc.wait(timeout=stop_grace)
            except subprocess.TimeoutExpired:
                result['forcedTermination'] = True
                proc.terminate(); proc.wait(timeout=15)
        reader.join(timeout=10)
        if reader.is_alive():
            result['monitorError'] = 'Output monitor did not finish'
        if proc.stdin and not proc.stdin.closed:
            proc.stdin.close()
        proc.stdout.close()
    result['success'] = (result['serverReady'] and result['testScopeConfirmed'] and result['stopRequested']
                         and result['exitCode'] == 0 and not result['timedOut']
                         and not result['forcedTermination'] and not result['mixinErrors'] and not result['monitorError'])
    return result


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--validate-only', action='store_true', help='Validate prepared scope without starting a server')
    args = parser.parse_args()
    if not 30 <= args.timeout <= 900:
        parser.error('Startzeitlimit muss zwischen 30 und 900 Sekunden liegen')
    root = validate(args.server)
    version = subprocess.run([args.java, '--version'], capture_output=True, text=True, timeout=15)
    if version.returncode or not re.search(r'^(?:openjdk|java)\s+25(?:\.|\s)', version.stdout, re.M):
        parser.error('Java 25 erforderlich')
    if args.validate_only:
        print(json.dumps({'scopeValid': True, 'server': str(root), 'serverStarted': False}, indent=2))
        return
    stamp = time.strftime('%Y%m%d-%H%M%S', time.gmtime())
    logfile = root / ('smoke-' + stamp + '.log')
    command = [args.java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               '-Ddulliehausen.phase2Test=true', '-Dmixin.debug.export=true',
               '-Xms512M', '-Xmx2G', '-jar', 'fabric-server-launch.jar', 'nogui']
    started_ns = time.time_ns()
    result = run_process(command, root, logfile, timeout=args.timeout)
    result['log'] = str(logfile)
    result['exportedMixinTargets'] = {}
    for name, marker in TARGETS.items():
        path = root / '.mixin.out/class' / (name + '.class')
        found = path.is_file() and path.stat().st_mtime_ns >= started_ns and marker in path.read_bytes()
        result['exportedMixinTargets'][name] = bool(found)
    result['allMixinTargetsObserved'] = all(result['exportedMixinTargets'].values())
    result['note'] = 'Exported transformed classes are evidence of application, not gameplay correctness.'
    result_file = root / ('smoke-' + stamp + '-result.json')
    result_file.write_text(json.dumps(result, indent=2), encoding='utf-8')
    (root / 'LAST-SMOKE-RESULT.txt').write_text(str(result_file), encoding='utf-8')
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['success'] else 1)


if __name__ == '__main__':
    main()
