# ==============================================================================
# SmartKhata — Windows Packaging & Standalone Bundle Generator
# ==============================================================================
# Requirements:
# - JDK 21 LTS (with jlink and jpackage in PATH)
# - Maven 3.9+ (mvn in PATH)
# - Windows PowerShell 5.1+ / PowerShell 7+
# ==============================================================================

param (
    [string]$AppVersion = "1.0.0",
    [string]$Vendor = "SmartKhata OpenSource / Local Business Software",
    [string]$Description = "Offline Desktop Bookkeeping and Inventory Management for Bangladesh Businesses",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Get-Item $PSScriptRoot\..).FullName
Set-Location $ProjectRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Building SmartKhata Standalone Windows Distribution v$AppVersion" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Maven Clean & Package
Write-Host "[1/6] Running Maven build..." -ForegroundColor Green
if ($SkipTests) {
    mvn clean package -DskipTests
} else {
    mvn clean test package
}

if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven build failed."
    exit 1
}

# 2. Gather Runtime Dependencies
Write-Host "[2/6] Copying runtime dependencies to target/libs..." -ForegroundColor Green
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/libs

# 3. Create Package Input Staging Directory
Write-Host "[3/6] Staging jars into target/package-input..." -ForegroundColor Green
$PackageInput = "$ProjectRoot\target\package-input"
if (Test-Path $PackageInput) {
    Remove-Item -Recurse -Force $PackageInput
}
New-Item -ItemType Directory -Force -Path $PackageInput | Out-Null
Copy-Item "$ProjectRoot\target\smartkhata-$AppVersion-SNAPSHOT.jar" $PackageInput\
Copy-Item "$ProjectRoot\target\libs\*.jar" $PackageInput\

# 4. Generate Stripped Modular JRE via jlink
Write-Host "[4/6] Trimming custom JRE runtime via jlink..." -ForegroundColor Green
$RuntimeDir = "$ProjectRoot\target\custom-runtime"
if (Test-Path $RuntimeDir) {
    Remove-Item -Recurse -Force $RuntimeDir
}
jlink --no-header-files `
      --no-man-pages `
      --strip-debug `
      --add-modules java.base,java.desktop,java.sql,java.naming,java.xml,jdk.unsupported,java.logging,jdk.crypto.ec,java.scripting,java.management,java.net.http,jdk.crypto.cryptoki `
      --output $RuntimeDir

# 5. Build Native App Image via jpackage
Write-Host "[5/6] Generating native SmartKhata.exe and app image via jpackage..." -ForegroundColor Green
$DistDir = "$ProjectRoot\dist"
if (!(Test-Path $DistDir)) {
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
}
$SmartKhataAppDir = "$DistDir\SmartKhata"
if (Test-Path $SmartKhataAppDir) {
    Remove-Item -Recurse -Force $SmartKhataAppDir
}

$IconPath = "$ProjectRoot\src\main\resources\com\smartkhata\ui\images\icons\smartkhata.ico"

jpackage --type app-image `
         --dest $DistDir `
         --name "SmartKhata" `
         --app-version $AppVersion `
         --vendor $Vendor `
         --copyright "Copyright (c) 2026 SmartKhata Contributors" `
         --description $Description `
         --icon $IconPath `
         --runtime-image $RuntimeDir `
         --input $PackageInput `
         --main-jar "smartkhata-$AppVersion-SNAPSHOT.jar" `
         --main-class "com.smartkhata.app.Launcher"

if ($LASTEXITCODE -ne 0) {
    Write-Error "jpackage failed to generate app image."
    exit 1
}

# 6. Compress Standalone Distribution Archive
Write-Host "[6/6] Creating standalone distribution ZIP archive..." -ForegroundColor Green
$ZipTarget = "$DistDir\SmartKhata-$AppVersion-windows-x64.zip"
if (Test-Path $ZipTarget) {
    Remove-Item -Force $ZipTarget
}
Compress-Archive -Path $SmartKhataAppDir -DestinationPath $ZipTarget -Force

$AppSizeMB = [math]::Round(((Get-ChildItem -Path $SmartKhataAppDir -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB), 2)
$ZipSizeMB = [math]::Round(((Get-Item $ZipTarget).Length / 1MB), 2)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " SmartKhata Build & Packaging Completed Successfully!" -ForegroundColor Green
Write-Host " App Directory: $SmartKhataAppDir ($AppSizeMB MB)" -ForegroundColor White
Write-Host " Portable Zip:  $ZipTarget ($ZipSizeMB MB)" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Note for WiX MSI Installer:" -ForegroundColor Yellow
Write-Host "To generate an MSI package, ensure WiX Toolset 3.11+ is installed with light.exe/candle.exe in PATH," -ForegroundColor Yellow
Write-Host "then execute:" -ForegroundColor Yellow
Write-Host "jpackage --type msi --dest dist --name SmartKhata --app-version $AppVersion --icon `"$IconPath`" --runtime-image `"$RuntimeDir`" --input `"$PackageInput`" --main-jar `"smartkhata-$AppVersion-SNAPSHOT.jar`" --main-class `"com.smartkhata.app.Launcher`" --win-dir-chooser --win-menu --win-shortcut --win-per-user-install" -ForegroundColor Cyan

