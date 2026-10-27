#!/usr/bin/env python3
"""Direct javac check with local cached jars; never creates a release mod JAR.

This is not Loom dependency resolution, annotation processing, Mixin application,
or an in-game test. Prefer a successful Gradle build for release validation.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-cache', type=Path, required=True, help='Directory containing modules-2 and fabric-loom')
    parser.add_argument('--output', type=Path, required=True, help='Writable scratch directory outside release artifacts')
    parser.add_argument('--minecraft-jar', type=Path)
    parser.add_argument('--run-minecraft-tests', action='store_true')
    args = parser.parse_args()
    cache = args.gradle_cache.resolve()
    mc = args.minecraft_jar or cache / 'fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'
    if not mc.is_file():
        parser.error('Cached Minecraft 26.3 JAR not found')
    modules = cache / 'modules-2/files-2.1'
    jars = {}
    for jar in sorted(modules.glob('*/*/*/*/*.jar')):
        if jar.name.endswith(('-sources.jar', '-javadoc.jar')):
            continue
        parts = jar.relative_to(modules).parts
        key = ('26.3' in parts[2], tuple(int(n) for n in re.findall(r'\d+', parts[2])), parts[2])
        if parts[:2] not in jars or key > jars[parts[:2]][0]:
            jars[parts[:2]] = (key, jar)
    if not jars:
        parser.error('Dependency JARs missing')
    classpath = [mc.resolve(), *(entry[1] for entry in jars.values())]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes = Path(tempfile.mkdtemp(prefix='classes-', dir=output))
    sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
    if args.run_minecraft_tests:
        sources += sorted((ROOT / 'tests/minecraft').glob('*.java'))
    compile_args = ['-proc:none', '-encoding', 'UTF-8', '-cp', ';'.join(p.as_posix() for p in classpath),
                    '-d', classes.as_posix(), *(p.as_posix() for p in sources)]

    def argument_file(path, values):
        # JVM argument files use escaped double quotes and backslashes inside strings.
        path.write_text('\n'.join('"' + str(value).replace('\\', '/').replace('"', '\\"') + '"'
                                  for value in values), encoding='utf-8')

    argfile = output / 'compile.args'
    argument_file(argfile, compile_args)
    result = subprocess.run(['javac', '@' + str(argfile)], capture_output=True, text=True, errors='replace', timeout=90)
    log = result.stdout + result.stderr
    (output / 'compile.log').write_text(log, encoding='utf-8')
    missing = []
    for source in sources:
        package = re.search(r'^package\s+([\w.]+);', source.read_text(encoding='utf-8'), re.M)
        expected = classes.joinpath(*(package.group(1).split('.') if package else []), source.stem + '.class')
        if not expected.is_file():
            missing.append(source.name)
    report = {'sourceFiles': len(sources), 'jars': [str(path) for path in classpath],
              'compilerExit': result.returncode, 'compilerException': 'Exception' in log,
              'missingPrimaryClasses': missing, 'classes': str(classes), 'releaseBuild': False}
    if result.returncode == 0 and not missing and args.run_minecraft_tests:
        exits = {}
        test_logs = []
        for source in sorted((ROOT / 'tests/minecraft').glob('*.java')):
            name = source.stem
            runfile = output / (name + '.args')
            argument_file(runfile, ['-cp', ';'.join(path.as_posix() for path in [classes, *classpath]), name])
            tested = subprocess.run(['java', '@' + str(runfile)], cwd=output,
                                    capture_output=True, text=True, errors='replace', timeout=90)
            text = name + '\n' + tested.stdout + tested.stderr
            (output / (name + '.log')).write_text(text, encoding='utf-8')
            test_logs.append(text)
            exits[name] = tested.returncode
            print(text)
        (output / 'minecraft-test.log').write_text('\n'.join(test_logs), encoding='utf-8')
        report['minecraftTestExits'] = exits
    (output / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(f'Sources: {len(sources)}, compiler exit: {result.returncode}, missing primary classes: {len(missing)}')
    if report['compilerException']:
        print('Compiler exception recorded: inspect compile.log; this is not a clean build.')
    raise SystemExit(1 if result.returncode or missing or any(report.get('minecraftTestExits', {}).values()) else 0)


if __name__ == '__main__':
    main()
