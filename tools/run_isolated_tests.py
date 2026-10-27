#!/usr/bin/env python3
"""JDK + Gson checks. Logger stub only; NOT a Fabric integration build.
Usage: python tools/run_isolated_tests.py --gson /path/to/gson.jar
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import re

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gson', required=True, type=Path)
    parser.add_argument('--test', action='append', help='Run only named test program(s); still compile all')
    args = parser.parse_args()
    gson = args.gson.resolve()
    if not gson.is_file():
        parser.error('Gson JAR missing')
    with tempfile.TemporaryDirectory(prefix='dullie-tests-', dir=ROOT.parent) as scratch:
        temp = Path(scratch)
        stub = temp / 'ErzmarkenMod.java'
        stub.write_text('''package de.baluserver.erzmarken;
public final class ErzmarkenMod {
 public static final Logger LOGGER = new Logger();
 public static final class Logger {
  public void error(String text, Object... args) { }
  public void warn(String text, Object... args) { }
  public void info(String text, Object... args) { }
 }
}''', encoding='utf-8')
        sources = [stub]
        for source in (ROOT / 'src/main/java/de/baluserver/erzmarken').glob('*.java'):
            text = source.read_text(encoding='utf-8')
            if source.name in ('ErzmarkenMod.java', 'ShopRecoveryContentAudit.java'):
                continue
            if 'net.minecraft' in text or 'net.fabricmc' in text:
                continue
            sources.append(source)
        tests = sorted((ROOT / 'tests').glob('*.java'))
        classes = temp / 'classes'
        classes.mkdir()
        def run(command):
            print('+', command[0], f'compile {len(sources)} sources and {len(tests)} test programs' if command[0] == 'javac' else command[-1], flush=True)
            subprocess.run(list(map(str, command)), cwd=ROOT, check=True, timeout=45)
        run(['javac', '-encoding', 'UTF-8', '-cp', gson, '-d', classes, *sources, *tests])
        failures = []
        selected = [test for test in tests if not args.test or test.stem in args.test]
        if args.test and set(args.test) - {test.stem for test in tests}:
            parser.error('Unknown test name')
        for test in selected:
            package = re.search(r'^package\s+([\w.]+);', test.read_text(encoding='utf-8'), re.M)
            name = (package.group(1) + '.' if package else '') + test.stem
            try:
                run(['java', '-cp', str(classes) + os.pathsep + str(gson), name])
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                failures.append(name)
        print(f'Java runtime programs: {len(selected) - len(failures)} passed / {len(failures)} failed. Logger stub used; no Fabric APIs compiled. Review compiler diagnostics separately.')
        if failures:
            print('FAILED:', ', '.join(failures))
            raise SystemExit(1)

if __name__ == '__main__':
    main()
