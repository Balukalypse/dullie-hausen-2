param([switch]$Phase2Test, [string]$GradleExecutable, [switch]$Offline)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

try {
    # java -version prints to STDERR. Under Windows PowerShell 5.1, together
    # with ErrorActionPreference=Stop this can abort a perfectly valid build.
    # Java 25 supports --version, which prints to STDOUT instead.
    $javaOutput = (& java --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Java konnte nicht gestartet werden.' }
    $javacOutput = (& javac --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Der Java-Compiler (javac) konnte nicht gestartet werden.' }

    $javaVersion = [regex]::Match($javaOutput, '(?m)^\s*(?:openjdk|java)\s+(\d+)(?:\.|\s|$)')
    $javacVersion = [regex]::Match($javacOutput, '(?m)^\s*javac\s+(\d+)(?:\.|\s|$)')
    if (-not $javaVersion.Success) {
        throw "Java-Version nicht erkannt: $javaOutput"
    }
    if (-not $javacVersion.Success) {
        throw "Compiler-Version nicht erkannt: $javacOutput"
    }
    if ([int]$javaVersion.Groups[1].Value -lt 25 -or [int]$javacVersion.Groups[1].Value -lt 25) {
        throw "JDK 25 oder neuer erforderlich. Gefunden: $($javaOutput.Split([Environment]::NewLine)[0]) / $javacOutput"
    }
    if ($javaVersion.Groups[1].Value -ne $javacVersion.Groups[1].Value) {
        throw "Java und javac haben unterschiedliche Hauptversionen: $($javaVersion.Groups[1].Value) / $($javacVersion.Groups[1].Value). Bitte PATH und JAVA_HOME pruefen."
    }
    Write-Host "Java erkannt: $($javaOutput.Split([Environment]::NewLine)[0])" -ForegroundColor Green
    Write-Host "Compiler erkannt: $javacOutput" -ForegroundColor Green

    # Portable JDK selection: respect JAVA_HOME (if set) or the current PATH.
    # No machine-specific org.gradle.java.home in gradle.properties.
    if ($env:JAVA_HOME) {
        $javaHomeExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
        $javacHomeExe = Join-Path $env:JAVA_HOME 'bin\javac.exe'
        if (-not (Test-Path $javaHomeExe) -or -not (Test-Path $javacHomeExe)) {
            throw "JAVA_HOME zeigt nicht auf ein komplettes JDK: $env:JAVA_HOME"
        }
        $homeVersionText = (& $javaHomeExe --version | Out-String).Trim()
        $homeVersion = [regex]::Match($homeVersionText, '(?m)^\s*(?:openjdk|java)\s+(\d+)(?:\.|\s|$)')
        if (-not $homeVersion.Success -or [int]$homeVersion.Groups[1].Value -ne 25) {
            throw "JAVA_HOME muss auf JDK 25 zeigen: $env:JAVA_HOME"
        }
    }
    $base = Join-Path $Root '.tools'
    # Loom opens archives through Java's real-path/ZIP filesystem APIs. A normal
    # read can succeed while these APIs are denied in a restricted environment.
    $probeErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & java (Join-Path $Root 'tools\BuildEnvironmentProbe.java') $Root
        $probeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $probeErrorActionPreference
    }
    if ($probeExitCode -ne 0) {
        throw 'Java kann hier keine Archive ueber den von Loom benoetigten Dateizugriff oeffnen. Bitte Build lokal mit passendem Java-/Windows-Zugriff ausfuehren.'
    }
    $gradleHome = Join-Path $base 'gradle-9.7.1'
    $gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
    if ($GradleExecutable) { $gradleBat = (Resolve-Path -LiteralPath $GradleExecutable).Path }
    if (-not (Test-Path $gradleBat)) {
        if ($Offline) { throw 'Gradle 9.7.1 lokal nicht vorhanden. Pfad ueber -GradleExecutable angeben.' }
        New-Item -ItemType Directory -Path $base -Force | Out-Null
        $zip = Join-Path $base 'gradle-9.7.1-bin.zip'
        Write-Host 'Lade Gradle 9.7.1 einmalig herunter ...'
        Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-9.7.1-bin.zip' -OutFile $zip
        $expected = 'acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a'
        $actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw 'Gradle-Archiv Hash falsch. Download nicht vertrauenswuerdig.' }
        Expand-Archive -Path $zip -DestinationPath $base -Force
        Remove-Item $zip -Force
    }

    $gradleVersionText = (& $gradleBat --version | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Gradle-Version konnte nicht geprueft werden.' }
    $gradleVersion = [regex]::Match($gradleVersionText, '(?m)^Gradle\s+(\d+)\.(\d+)')
    if (-not $gradleVersion.Success -or [int]$gradleVersion.Groups[1].Value -lt 9 -or
        ([int]$gradleVersion.Groups[1].Value -eq 9 -and [int]$gradleVersion.Groups[2].Value -lt 7)) {
        throw 'Loom 1.18.2 benoetigt Gradle ab 9.7. Die angegebene Distribution ist ungeeignet.'
    }
    Write-Host ('Baue Fabric 26.3; Phase-2-Testprofil: ' + $Phase2Test)
    # Gradle may write warnings or diagnostics to STDERR. Do not interpret
    # those lines as PowerShell exceptions; evaluate Gradle's exit code.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $buildArguments = @('build', '--no-daemon')
        if ($Phase2Test) { $buildArguments += '-Pphase2Test=true' }
        if ($Offline) { $buildArguments += '--offline' }
        & $gradleBat @buildArguments
        $gradleExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($gradleExitCode -ne 0) {
        throw "Gradle-Build fehlgeschlagen (Exitcode $gradleExitCode). Bitte die Fehlermeldungen weiter oben kopieren."
    }

    # Build-Ergebnis dynamisch ermitteln, statt eine veraltete Versionsnummer
    # fest einzutragen. Quellen- und Entwicklungs-JARs sind keine Server-Mods.
    $libsDir = Join-Path $Root 'build\libs'
    $releaseJars = @(
        Get-ChildItem -LiteralPath $libsDir -Filter 'erzmarken-*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch '(?i)-(sources|dev|javadoc|all)\.jar$' } |
            Where-Object { if ($Phase2Test) { $_.Name -like 'erzmarken-PHASE2-TEST-*' } else { $_.Name -notlike 'erzmarken-PHASE2-TEST-*' } } |
            Sort-Object LastWriteTimeUtc -Descending
    )
    if ($releaseJars.Count -eq 0) {
        throw "Gradle meldet Erfolg, aber unter $libsDir wurde keine releasefaehige erzmarken-*.jar gefunden. Bitte Inhalt von build\libs pruefen."
    }
    $outputJar = $releaseJars[0].FullName
    $report = Get-Content -LiteralPath (Join-Path $Root 'build\phase2-build-report.json') -Raw | ConvertFrom-Json
    if ([bool]$report.testBuild -ne [bool]$Phase2Test -or $report.jar -ne $releaseJars[0].Name -or
        (Get-FileHash -LiteralPath $outputJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $report.sha256) {
        throw 'Buildbericht und ausgewaehlte JAR passen nicht zusammen.'
    }
    Write-Host "FERTIG: $outputJar" -ForegroundColor Green
    if ($releaseJars.Count -gt 1) {
        Write-Host "Hinweis: Mehrere Mod-JARs vorhanden. Ausgewaehlt wurde die zuletzt geaenderte Datei." -ForegroundColor Yellow
    }
    exit 0
}
catch {
    Write-Host ('FEHLER: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
