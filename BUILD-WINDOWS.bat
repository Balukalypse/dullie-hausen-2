@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-windows.ps1"
echo.
if errorlevel 1 (
    echo FEHLER: Mod konnte nicht gebaut werden. Bitte Fehlermeldung speichern.
) else (
    echo FERTIG. Schau in den Ordner build\libs.
)
pause
