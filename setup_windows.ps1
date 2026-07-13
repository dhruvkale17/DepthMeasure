#Requires -Version 5.0

<#
.SYNOPSIS
    ARCore Depth Measure App - Windows Setup Script (D:-drive, portable-zip method)
.DESCRIPTION
    Installs the build toolchain entirely under D:\Android using portable zips
    (no Chocolatey, nothing on C:):
      - Microsoft OpenJDK 17          -> D:\Android\jdk
      - Android SDK command-line tools -> D:\Android\sdk\cmdline-tools\latest
      - platform-tools, platforms;android-34, build-tools;34.0.0
    Sets JAVA_HOME / ANDROID_HOME / PATH at User scope.

    This mirrors the exact steps that were verified on this machine. JDK 17 is
    used (not 11) because Android Gradle Plugin 8.x refuses to run on JDK 11;
    the app itself still targets Java 11 source/target compatibility.
.EXAMPLE
    .\setup_windows.ps1
    .\setup_windows.ps1 -Help
#>

param(
    [switch]$Help,
    [string]$Root = "D:\Android"
)

$ErrorActionPreference = "Stop"

function Write-Info    { param($msg) Write-Host "[i] $msg" -ForegroundColor Cyan }
function Write-Success { param($msg) Write-Host "[+] $msg" -ForegroundColor Green }
function Write-Warn    { param($msg) Write-Host "[!] $msg" -ForegroundColor Yellow }
function Write-ErrorMsg{ param($msg) Write-Host "[x] $msg" -ForegroundColor Red }

if ($Help) {
    Write-Host @"
ARCore Depth Measure - Windows Setup (portable, D:-drive)

Usage: .\setup_windows.ps1 [-Root <path>] [-Help]

Options:
  -Root <path>   Install root for all tools (default: D:\Android)
  -Help          Show this help message

Installs (all under -Root, nothing on C:):
  - Microsoft OpenJDK 17
  - Android SDK command-line tools
  - platform-tools, platforms;android-34, build-tools;34.0.0

Requirements:
  - Windows 10/11 x64
  - Internet connection
  - ~2 GB free disk space
"@
    exit 0
}

$JdkUrl = "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip"
$CmdlineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"

$Downloads = Join-Path $Root "downloads"
$JdkDir    = Join-Path $Root "jdk"
$SdkDir    = Join-Path $Root "sdk"

Write-Host "ARCore Depth Measure - Windows Setup (root: $Root)" -ForegroundColor Magenta
Write-Host "=======================================================" -ForegroundColor Magenta

New-Item -ItemType Directory -Force -Path $Downloads, $JdkDir, $SdkDir | Out-Null

# ---------------------------------------------------------------
# Step 1: Java 17
# ---------------------------------------------------------------
Write-Info "Step 1/4: OpenJDK 17..."
$javaExe = Get-ChildItem -Path $JdkDir -Filter java.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $javaExe) {
    $zip = Join-Path $Downloads "jdk17.zip"
    Write-Info "Downloading OpenJDK 17..."
    curl.exe -L -s -o $zip $JdkUrl
    Write-Info "Extracting..."
    Expand-Archive -Path $zip -DestinationPath $JdkDir -Force
    $javaExe = Get-ChildItem -Path $JdkDir -Filter java.exe -Recurse | Select-Object -First 1
}
$JavaHome = Split-Path (Split-Path $javaExe.FullName)   # <jdk>\bin\java.exe -> <jdk>
Write-Success "JDK: $JavaHome"

# ---------------------------------------------------------------
# Step 2: Android SDK command-line tools
# ---------------------------------------------------------------
Write-Info "Step 2/4: Android SDK command-line tools..."
$cmdlineLatest = Join-Path $SdkDir "cmdline-tools\latest"
if (-not (Test-Path (Join-Path $cmdlineLatest "bin\sdkmanager.bat"))) {
    $zip = Join-Path $Downloads "cmdline-tools.zip"
    Write-Info "Downloading command-line tools..."
    curl.exe -L -s -o $zip $CmdlineToolsUrl
    Write-Info "Extracting..."
    $tmp = Join-Path $SdkDir "_tmp"
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path $cmdlineLatest | Out-Null
    Move-Item -Path (Join-Path $tmp "cmdline-tools\*") -Destination $cmdlineLatest -Force
    Remove-Item -Path $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Success "cmdline-tools present"

# ---------------------------------------------------------------
# Step 3: Environment variables (User scope)
# ---------------------------------------------------------------
Write-Info "Step 3/4: Environment variables (User scope)..."
[Environment]::SetEnvironmentVariable("JAVA_HOME", $JavaHome, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $SdkDir, "User")

$paths = @(
    (Join-Path $JavaHome "bin"),
    (Join-Path $SdkDir "platform-tools"),
    (Join-Path $SdkDir "cmdline-tools\latest\bin")
)
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
foreach ($p in $paths) { if ($userPath -notlike "*$p*") { $userPath = "$userPath;$p" } }
[Environment]::SetEnvironmentVariable("Path", $userPath, "User")

# Make them live for THIS session too
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkDir
$env:Path = "$($paths -join ';');$env:Path"
Write-Success "JAVA_HOME / ANDROID_HOME / PATH set"

# ---------------------------------------------------------------
# Step 4: SDK components
# ---------------------------------------------------------------
Write-Info "Step 4/4: SDK components (licenses + platform-tools, android-34, build-tools 34.0.0)..."
$sdkManager = Join-Path $cmdlineLatest "bin\sdkmanager.bat"
$yes = (@('y') * 30) -join "`n"
$yes | & $sdkManager --sdk_root=$SdkDir --licenses | Out-Null
& $sdkManager --sdk_root=$SdkDir "platform-tools" "platforms;android-34" "build-tools;34.0.0" | Select-Object -Last 3

# ---------------------------------------------------------------
# Verify
# ---------------------------------------------------------------
Write-Host ""
Write-Host "=======================================================" -ForegroundColor Magenta
Write-Info "Verifying..."
& (Join-Path $JavaHome "bin\java.exe") -version
if (Test-Path (Join-Path $SdkDir "platform-tools\adb.exe")) {
    Write-Success "ADB: $(Join-Path $SdkDir 'platform-tools\adb.exe')"
} else {
    Write-ErrorMsg "adb.exe not found - SDK install incomplete"
}

Write-Host ""
Write-Success "Setup complete."
Write-Host "Next: close & reopen PowerShell, then from the project folder run:" -ForegroundColor Cyan
Write-Host "  .\build_deploy_windows.ps1 full"
