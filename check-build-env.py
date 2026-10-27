#!/usr/bin/env python3
"""Offline build prerequisite checker. Does not modify project or download dependencies."""
import os, re, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def check():
    results=[]
    for cmd in ('java','javac'):
        exe=shutil.which(cmd)
        if not exe:
            results.append((False, f'{cmd}: nicht gefunden (PATH/JAVA_HOME pruefen)'))
            continue
        try:
            proc=subprocess.run([exe,'--version'],capture_output=True,text=True,timeout=10)
        except (OSError,subprocess.TimeoutExpired) as exc:
            results.append((False,f'{cmd}: Aufruf fehlgeschlagen: {exc}'))
            continue
        result=(proc.stdout+proc.stderr).strip()
        version=re.search(r'(?m)^(?:openjdk|java|javac)\s+(\d+)(?:\.|\s|$)',result)
        major=int(version.group(1)) if version else None
        results.append((proc.returncode==0 and major==25, f'{cmd}: {major or "unbekannt"} (benoetigt genau JDK 25)'))
    gradle_wrapper=(ROOT/'gradlew').is_file() or (ROOT/'gradlew.bat').is_file()
    gradle_windows=(ROOT/'build-windows.ps1').is_file()
    results.append((gradle_wrapper or gradle_windows,f'Build-Einstieg: {"Gradle Wrapper" if gradle_wrapper else "Windows PowerShell" if gradle_windows else "fehlt"}'))
    build=(ROOT/'build.gradle').read_text(encoding='utf-8')
    props=(ROOT/'gradle.properties').read_text(encoding='utf-8')
    results.append(('options.release = 25' in build and 'JavaVersion.VERSION_25' in build,'Java-Ziel: 25'))
    results.append(('org.gradle.java.home=' not in props,'Kein fest verdrahteter lokaler JDK-Pfad'))
    for ok,label in results: print(('OK   ' if ok else 'FEHLER ')+label)
    if not all(ok for ok,_ in results):
        print('Build-Vorbedingungen noch nicht vollstaendig. Kein Fabric-Build ausgefuehrt.')
        return 1
    print('Lokale Voraussetzungen passen. Fabric-Abhaengigkeiten/Build noch separat pruefen.')
    return 0

if __name__=='__main__': sys.exit(check())
