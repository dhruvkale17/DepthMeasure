#Requires -Version 5.0

<#
.SYNOPSIS
    ARCore Depth Measure App - Windows Build & Deploy Helper
.DESCRIPTION
    Build, install, run, and debug the Android app from PowerShell.
.EXAMPLE
    .\build_deploy_windows.ps1 full
    .\build_deploy_windows.ps1 logs
#>

param(
    [Parameter(Position=0)]
    [ValidateSet("build","install","run","logs","clean","uninstall","full","device","help")]
    [string]$Command = "help"
)

$PackageName = "com.example.depthmeasure"
$ActivityName = ".MainActivity"

function Write-Info { param($msg) Write-Host "ℹ $msg" -ForegroundColor Cyan }
function Write-Success { param($msg) Write-Host "✓ $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "⚠ $msg" -ForegroundColor Yellow }
function Write-ErrorMsg { param($msg) Write-Host "✗ $msg" -ForegroundColor Red }

function Test-Adb {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Write-ErrorMsg "adb not found. Ensure ANDROID_HOME is set and platform-tools is in PATH."
        return $false
    }
    return $true
}

function Test-DeviceConnected {
    if (-not (Test-Adb)) { return $false }
    $devices = & adb devices | Select-String -Pattern "\tdevice$"
    if ($devices.Count -eq 0) {
        Write-Warn "No device connected. Connect your S25 via USB and enable USB Debugging."
        return $false
    }
    return $true
}

function Invoke-Build {
    Write-Info "Building debug APK..."
    if (-not (Test-Path ".\gradlew.bat")) {
        Write-ErrorMsg "gradlew.bat not found. Run this script from your project root."
        return $false
    }
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -eq 0) {
        Write-Success "APK built successfully"
        Write-Info "Location: app\build\outputs\apk\debug\app-debug.apk"
        return $true
    } else {
        Write-ErrorMsg "Build failed"
        return $false
    }
}

function Invoke-Install {
    Write-Info "Installing APK to device..."
    if (-not (Test-DeviceConnected)) { return $false }

    & .\gradlew.bat installDebug
    if ($LASTEXITCODE -eq 0) {
        Write-Success "APK installed"
        return $true
    } else {
        Write-ErrorMsg "Installation failed"
        return $false
    }
}

function Invoke-Run {
    Write-Info "Launching app..."
    if (-not (Test-DeviceConnected)) { return $false }

    & adb shell am start -n "$PackageName/$ActivityName"
    if ($LASTEXITCODE -eq 0) {
        Write-Success "App launched"
        Start-Sleep -Seconds 2
        return $true
    } else {
        Write-ErrorMsg "Failed to launch app"
        return $false
    }
}

function Invoke-Logs {
    Write-Info "Streaming logs (Ctrl+C to stop)..."
    if (-not (Test-Adb)) { return }
    & adb logcat -s DepthMeasure
}

function Invoke-Clean {
    Write-Info "Cleaning build artifacts..."
    & .\gradlew.bat clean
    Write-Success "Build cache cleaned"
}

function Invoke-Uninstall {
    Write-Info "Uninstalling app..."
    if (-not (Test-Adb)) { return }
    & adb uninstall $PackageName
    Write-Success "App uninstalled (if it was installed)"
}

function Invoke-Full {
    Write-Info "Running full build-deploy-run cycle..."
    Write-Host ""

    if (-not (Invoke-Build)) { return }
    Write-Host ""

    if (-not (Invoke-Install)) { return }
    Write-Host ""

    if (-not (Invoke-Run)) { return }
    Write-Host ""

    Write-Success "Full cycle complete! Showing logs..."
    Write-Host ""
    Invoke-Logs
}

function Show-DeviceInfo {
    Write-Info "Device Information:"
    Write-Host ""

    if (-not (Test-Adb)) { return }

    Write-Info "Connected device(s):"
    & adb devices
    Write-Host ""

    if (Test-DeviceConnected) {
        Write-Info "Device details:"
        $model = & adb shell getprop ro.product.model
        $version = & adb shell getprop ro.build.version.release
        $hardware = & adb shell getprop ro.hardware
        Write-Host "  Model: $model"
        Write-Host "  Android version: $version"
        Write-Host "  Hardware: $hardware"
        Write-Host ""

        Write-Info "App status:"
        $installed = & adb shell pm list packages | Select-String $PackageName
        if ($installed) {
            Write-Success "App is installed"
        } else {
            Write-Warn "App is not installed"
        }
    }
}

function Show-Help {
    Write-Host @"
ARCore Depth Measure - Windows Build Helper

Usage: .\build_deploy_windows.ps1 [COMMAND]

Commands:
  build          Build debug APK
  install        Install APK to connected device
  run            Launch app on device
  logs           Stream logcat output (DepthMeasure only)
  clean          Clean build cache
  uninstall      Uninstall app from device
  full           Full cycle: build -> install -> run -> logs
  device         Show connected device info
  help           Show this help message

Examples:
  .\build_deploy_windows.ps1 build
  .\build_deploy_windows.ps1 full
  .\build_deploy_windows.ps1 logs

Requirements:
  - Run from your Android project root (where gradlew.bat lives)
  - ANDROID_HOME set, platform-tools in PATH
  - Device connected via USB with USB Debugging enabled

Troubleshooting:
  - Device not found? Check USB connection and USB Debugging toggle
  - Build fails? Try: .\build_deploy_windows.ps1 clean
  - Script blocked? Run:
      Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
"@
}

switch ($Command) {
    "build"     { Invoke-Build }
    "install"   { Invoke-Install }
    "run"       { Invoke-Run }
    "logs"      { Invoke-Logs }
    "clean"     { Invoke-Clean }
    "uninstall" { Invoke-Uninstall }
    "full"      { Invoke-Full }
    "device"    { Show-DeviceInfo }
    "help"      { Show-Help }
    default     { Show-Help }
}
