@echo off
setlocal
cd /d "%~dp0"
echo ========================================================
echo  Installing SmartKhata Standalone Desktop Application
echo ========================================================
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-smartkhata.ps1"
echo.
pause

