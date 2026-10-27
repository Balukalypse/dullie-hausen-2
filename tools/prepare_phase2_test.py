#!/usr/bin/env python3
"""Prepare a NEW loopback-only test folder from a successful Gradle test build.
Never starts Minecraft, accepts the EULA, changes firewall rules, or reuses a world.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MARKER = '.dulliehausen-phase2-test.json'


def source_digest(project):
    files = [p for p in (project / 'src').rglob('*') if p.is_file()]
    files += [project / name for name in ('build.gradle', 'settings.gradle', 'gradle.properties')]
    lines = ''.join(p.relative_to(project).as_posix() + '\n' + hashlib.sha256(p.read_bytes()).hexdigest() + '\n'
                    for p in sorted(files, key=lambda p: p.relative_to(project).as_posix()))
    return hashlib.sha256(lines.encode()).hexdigest()


def safe_path(path):
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError('Verknuepfte Pfade sind fuer Testvorbereitung nicht zulaessig')


def metadata(path):
    with zipfile.ZipFile(path) as jar:
        info = jar.getinfo('fabric.mod.json')
        if info.file_size > 64 * 1024:
            raise ValueError('Mod-Metadaten zu gross')
        return json.loads(jar.read(info))


def validate(project, mod, report_file, launcher, api, destination, tester_specs):
    for path in (mod, report_file, launcher, api, destination):
        safe_path(path.absolute())
    if destination.exists() or destination.is_symlink():
        raise ValueError('Ziel existiert bereits. Nur ein neuer, eigener Testordner ist erlaubt.')
    report = json.loads(report_file.read_text(encoding='utf-8'))
    mod_info = metadata(mod)
    if report.get('schema') != 1 or report.get('task') != 'build' or report.get('testBuild') is not True:
        raise ValueError('Erfolgreicher Gradle-Testbuild-Bericht fehlt')
    if report.get('sourceDigest') != source_digest(project):
        raise ValueError('Buildbericht passt nicht zu den aktuellen Projektquellen; neu bauen')
    if report.get('jar') != mod.name or report.get('sha256') != hashlib.sha256(mod.read_bytes()).hexdigest():
        raise ValueError('JAR und Buildbericht stimmen nicht ueberein')
    version = re.match(r'^(\d+)\.(\d+)', str(report.get('gradle', '')))
    if not version or tuple(map(int, version.groups())) < (9, 7) or report.get('javaRelease') != 25 or report.get('minecraft') != '26.3':
        raise ValueError('Buildversionen passen nicht zu Java 25 / Minecraft 26.3 / Gradle 9.7+')
    if mod_info.get('id') != 'erzmarken' or mod_info.get('custom', {}).get('dulliehausen:phase2-test') != 'true':
        raise ValueError('Nur ausdruecklich markierte Phase-2-Test-JAR erlaubt')
    if mod_info.get('version') != report.get('version') or not str(mod_info.get('version', '')).endswith('-test'):
        raise ValueError('Testversion passt nicht zum Buildbericht')
    with zipfile.ZipFile(mod) as jar:
        required = ['de/baluserver/erzmarken/Phase2TestAccess.class', 'de/baluserver/erzmarken/mixin/MinecraftServerPlayerDataAccessor.class', 'erzmarken.mixins.json']
        if any(name not in jar.namelist() for name in required):
            raise ValueError('Test-JAR ist unvollstaendig')
    api_info = metadata(api)
    props = dict(line.split('=', 1) for line in (project / 'gradle.properties').read_text().splitlines()
                 if '=' in line and not line.lstrip().startswith('#'))
    if api_info.get('id') != 'fabric-api' or api_info.get('version') != props.get('fabric_api_version'):
        raise ValueError('Fabric-API passt nicht zur Projektversion')
    with zipfile.ZipFile(launcher) as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
        if not re.search(r'(?m)^Main-Class: net\.fabricmc\.installer\.ServerLauncher\s*$', manifest):
            raise ValueError('Selbstinstallierende Fabric-Server-Launcher-JAR erforderlich, kein Client-/Legacy-Launcher')
    testers, names = [], set()
    for spec in tester_specs:
        identifier, name = spec.split(':', 1)
        if str(uuid.UUID(identifier)) != identifier or not re.fullmatch(r'[A-Za-z0-9_]{3,16}', name):
            raise ValueError('Testkonto muss kanonische UUID:Spielername sein')
        if identifier in {p['uuid'] for p in testers} or name.lower() in names:
            raise ValueError('Doppelte Testkonten sind nicht erlaubt')
        names.add(name.lower()); testers.append({'uuid': identifier, 'name': name})
    if not 2 <= len(testers) <= 8:
        raise ValueError('Zwei bis acht eigene Testkonten eintragen')
    return testers


def prepare(project, mod, report, launcher, api, destination, tester_specs, dry_run=False):
    testers = validate(project, mod, report, launcher, api, destination, tester_specs)
    target = destination.resolve()
    world = target / 'test-world'
    marker = {'schema': 1, 'purpose': 'isolated-phase2-test', 'testId': str(uuid.uuid4()),
              'worldPath': str(world), 'testers': [p['uuid'] for p in testers],
              'features': ['inventory', 'buyback', 'recovery']}
    if dry_run:
        return {'dryRun': True, 'destination': str(target), 'testers': testers, 'startsServer': False}
    target.mkdir(parents=True, exist_ok=False)
    (target / 'mods').mkdir(); world.mkdir()
    shutil.copyfile(mod, target / 'mods' / mod.name)
    shutil.copyfile(api, target / 'mods/fabric-api.jar')
    shutil.copyfile(launcher, target / 'fabric-server-launch.jar')
    shutil.copyfile(report, target / 'phase2-build-report.json')
    (target / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25566\nlevel-name=test-world\n'
        'online-mode=true\nwhite-list=true\nenforce-whitelist=true\n'
        f'max-players={len(testers)}\ngamemode=survival\nspawn-protection=0\n'
        'enable-rcon=false\nenable-query=false\nenable-command-block=false\n'
        'motd=Dullie Hausen PHASE 2 TEST - keine Produktivwelt\n', encoding='utf-8')
    (target / 'whitelist.json').write_text(json.dumps(testers, indent=2), encoding='utf-8')
    (target / 'eula.txt').write_text('# EULA selbst lesen; nur bei eigener Zustimmung aendern.\neula=false\n', encoding='utf-8')
    (world / MARKER).write_text(json.dumps(marker, indent=2), encoding='utf-8')
    (target / 'TEST-LESEN.txt').write_text('Nur lokaler Testbetrieb auf 127.0.0.1:25566.\n'
        'Keine Produktivwelt hineinkopieren. Keine OP-Rechte fuer Testspieler erforderlich.\n'
        'EULA selbst lesen; dieses Werkzeug akzeptiert sie nicht. Fabric-Laufzeit muss korrekt eingerichtet sein.\n'
        'Ein Bootstrap-Launcher kann beim ersten Start Minecraft/Fabric herunterladen; Netzwerk wurde nicht freigegeben.\n'
        'Der Ordner darf nicht verschoben werden: das Weltkennzeichen ist an seinen absoluten Pfad gebunden.\n'
        'Ablauf im Projekt: ERSTER-PHASE2-TEST.md.\n', encoding='utf-8')
    (target / 'START-PHASE2-TEST.ps1').write_text('''$ErrorActionPreference = 'Stop'
$TestRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $TestRoot
if (-not (Select-String -LiteralPath (Join-Path $TestRoot 'eula.txt') -Pattern '^eula=true$' -Quiet)) {
    throw 'Bitte EULA selbst lesen und nur bei Zustimmung eula=true setzen.'
}
$javaText = (& java --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaText -notmatch '(?m)^(?:openjdk|java)\\s+25(?:\\.|\\s)') { throw 'Java 25 erforderlich.' }
$expectedWorld = Join-Path $TestRoot 'test-world'
$worldMarker = Get-Content -LiteralPath (Join-Path $expectedWorld '.dulliehausen-phase2-test.json') -Raw | ConvertFrom-Json
if ($worldMarker.worldPath -ne $expectedWorld) { throw 'Testordner wurde verschoben; Start verweigert.' }
& java '-Ddulliehausen.phase2Test=true' '-Xms1G' '-Xmx2G' -jar 'fabric-server-launch.jar' nogui
exit $LASTEXITCODE
''', encoding='utf-8')
    (target / 'START-PHASE2-TEST.bat').write_text('@echo off\r\npowershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0START-PHASE2-TEST.ps1"\r\npause\r\n', encoding='utf-8')
    return {'dryRun': False, 'destination': str(target), 'testers': testers, 'startsServer': False, 'eulaAccepted': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('mod', 'build-report', 'server-launcher', 'fabric-api', 'destination'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--tester', action='append', required=True, help='UUID:Spielername (zwei bis acht)')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    try:
        result = prepare(ROOT, args.mod, args.build_report, args.server_launcher, args.fabric_api,
                         args.destination, args.tester, args.dry_run)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, 'TESTVORBEREITUNG VERWEIGERT: ' + str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
