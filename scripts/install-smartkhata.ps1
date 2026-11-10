# ==============================================================================
# SmartKhata — Local Windows Desktop Installer & Shortcut Creator
# ==============================================================================
# Installs SmartKhata standalone to %LOCALAPPDATA%\Programs\SmartKhata
# Creates Desktop and Start Menu shortcuts without requiring Administrator privileges.
# User financial databases in %LOCALAPPDATA%\SmartKhata\data are 100% preserved.
# ==============================================================================

$ErrorActionPreference = "Stop"
$ProjectRoot = (Get-Item $PSScriptRoot\..).FullName
$SourceDir = "$ProjectRoot\dist\SmartKhata"

if (!(Test-Path "$SourceDir\SmartKhata.exe")) {
    Write-Error "SmartKhata build not found at $SourceDir. Please run scripts\package-windows-app.ps1 first."
    exit 1
}

$InstallDir = "$env:LOCALAPPDATA\Programs\SmartKhata"
Write-Host "Installing SmartKhata to: $InstallDir" -ForegroundColor Cyan

# 1. Create target installation folder and copy application binaries
if (!(Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
}
Copy-Item -Path "$SourceDir\*" -Destination $InstallDir -Recurse -Force

# 2. Helper to create Windows Shell Shortcuts
$WshShell = New-Object -ComObject WScript.Shell
$ExePath = "$InstallDir\SmartKhata.exe"

# Desktop Shortcut
$DesktopPath = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::Desktop)
$DesktopShortcut = $WshShell.CreateShortcut("$DesktopPath\SmartKhata.lnk")
$DesktopShortcut.TargetPath = $ExePath
$DesktopShortcut.WorkingDirectory = $InstallDir
$DesktopShortcut.Description = "SmartKhata - Offline Bookkeeping & Accounting"
$DesktopShortcut.IconLocation = "$ExePath,0"
$DesktopShortcut.Save()
Write-Host "Created Desktop Shortcut: $DesktopPath\SmartKhata.lnk" -ForegroundColor Green

# Start Menu Shortcut
$StartMenuPath = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::Programs)
$StartShortcut = $WshShell.CreateShortcut("$StartMenuPath\SmartKhata.lnk")
$StartShortcut.TargetPath = $ExePath
$StartShortcut.WorkingDirectory = $InstallDir
$StartShortcut.Description = "SmartKhata - Offline Bookkeeping & Accounting"
$StartShortcut.IconLocation = "$ExePath,0"
$StartShortcut.Save()
Write-Host "Created Start Menu Shortcut: $StartMenuPath\SmartKhata.lnk" -ForegroundColor Green

# 3. Create Uninstaller Script
$UninstallScript = @"
@echo off
echo =========================================================
echo  SmartKhata Uninstaller
echo =========================================================
echo.
echo Removing SmartKhata application files...
del /q "%USERPROFILE%\Desktop\SmartKhata.lnk" 2>nul
del /q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\SmartKhata.lnk" 2>nul
rd /s /q "%LOCALAPPDATA%\Programs\SmartKhata" 2>nul

echo.
echo =========================================================
echo  SmartKhata program binaries removed successfully.
echo.
echo  NOTE: Your financial books, customer dues, and SQLite
echo  database at "%LOCALAPPDATA%\SmartKhata\data\smartkhata.db"
echo  have been safely preserved.
echo =========================================================
pause
"@

Set-Content -Path "$InstallDir\uninstall.bat" -Value $UninstallScript -Encoding ASCII
Write-Host "Created Uninstaller: $InstallDir\uninstall.bat" -ForegroundColor Green

Write-Host ""
Write-Host "Installation Complete! You can now launch SmartKhata from your Desktop or Start Menu." -ForegroundColor Cyan

