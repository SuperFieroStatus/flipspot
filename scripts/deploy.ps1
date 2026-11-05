<#
.SYNOPSIS
  FlipSpot dev loop: build debug APK, install, launch, tail logcat.

.EXAMPLE
  .\scripts\deploy.ps1                 # build + install + launch + logcat
  .\scripts\deploy.ps1 -NoBuild        # reinstall last APK + launch + logcat
  .\scripts\deploy.ps1 -DeviceInfo     # print ABI, screen size/density, key-shortcut settings, then exit
  .\scripts\deploy.ps1 -GetEvent       # raw kernel key events (adb shell getevent -l) instead of logcat
  .\scripts\deploy.ps1 -Serial ABC123  # pick a device when several are attached

  Ctrl+C stops logcat/getevent. Works in Windows PowerShell 5.1 and PowerShell 7.
#>
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [switch]$Clean,
    [switch]$NoLaunch,
    [switch]$NoLogcat,
    [switch]$GetEvent,
    [switch]$DeviceInfo,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Package = 'dev.mike.flipspot'
$Activity = "$Package/.HomeActivity"
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# ---- Android SDK location (local.properties, env, or default) ----
$SdkDir = $null
$localProps = Join-Path $Root 'local.properties'
if (Test-Path $localProps) {
    $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if ($line) { $SdkDir = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $SdkDir) { $SdkDir = $env:ANDROID_HOME }
if (-not $SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
if (-not $SdkDir) { $SdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

# ---- adb ----
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) { $Adb = $adbCmd.Source }
else {
    $Adb = Join-Path $SdkDir 'platform-tools\adb.exe'
    if (-not (Test-Path $Adb)) { Fail "adb not found on PATH or in $SdkDir\platform-tools" }
}
$AdbArgs = @()
if ($Serial) { $AdbArgs = @('-s', $Serial) }
function Adb { & $Adb @AdbArgs @args }

# ---- JDK for Gradle (needs 17+; the PATH java here may be Java 8) ----
function Ensure-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'C:\Program Files\Android\Android Studio\jbr'
    foreach ($c in $candidates) {
        $release = Join-Path $c 'release'
        if (Test-Path $release) {
            $ver = (Get-Content $release | Where-Object { $_ -match '^JAVA_VERSION=' }) -replace '^JAVA_VERSION="?([0-9]+).*', '$1'
            if ([int]$ver -ge 17) { $env:JAVA_HOME = $c; return }
        }
    }
    Fail 'No JDK 17+ found. Set JAVA_HOME or install Android Studio (bundled JBR).'
}

function Require-Device {
    # PS 5.1 turns redirected native stderr into errors; don't let 'Stop' throw on "no devices".
    $ErrorActionPreference = 'Continue'
    $state = (Adb get-state) 2>$null
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne 'device') {
        Adb devices
        Fail 'No authorised device. Plug in the Flip 2, enable USB debugging, accept the RSA prompt (or pass -Serial).'
    }
}

# ---- Device info only ----
if ($DeviceInfo) {
    Require-Device
    Step 'Device info'
    # One adb call per value: PS 5.1 mangles nested quotes passed to native commands.
    foreach ($p in 'ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.product.cpu.abi', 'ro.product.cpu.abilist') {
        "{0,-26} {1}" -f $p, ((Adb shell getprop $p) -join '').Trim()
    }
    Adb shell wm size
    Adb shell wm density
    Step 'Key-shortcut settings (read-only)'
    # Flip 2 stores these in 'global' as keyshortcut_<dir>key=Label-package-activityClass
    foreach ($ns in 'system', 'secure', 'global') {
        Adb shell settings list $ns | Select-String -Pattern 'shortcut|upkey|downkey|leftkey|rightkey' |
            ForEach-Object { "${ns}: $_" }
    }
    exit 0
}

# ---- Build ----
if (-not $NoBuild) {
    Ensure-Jdk
    Step "Building debug APK (JAVA_HOME=$env:JAVA_HOME)"
    Push-Location $Root
    try {
        $tasks = @()
        if ($Clean) { $tasks += 'clean' }
        $tasks += 'assembleDebug'
        & (Join-Path $Root 'gradlew.bat') @tasks --console=plain
        if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
    $sizeKb = [math]::Round((Get-Item $Apk).Length / 1KB)
    Step "APK: $Apk ($sizeKb KB)"
}
if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk (run without -NoBuild)" }

# ---- Install + launch ----
Require-Device
Step 'Installing'
Adb install -r $Apk
if ($LASTEXITCODE -ne 0) { Fail 'adb install failed' }

if (-not $NoLaunch) {
    Adb logcat -c
    Step "Launching $Activity"
    Adb shell am start -S -n $Activity
    if ($LASTEXITCODE -ne 0) { Fail 'am start failed' }
}

# ---- Observe ----
if ($GetEvent) {
    Step 'Raw input events (press keys on the phone; Ctrl+C to stop)'
    Adb shell getevent -l
} elseif (-not $NoLogcat) {
    Step 'logcat FlipSpot / SpotifyAppRemote / crashes (Ctrl+C to stop)'
    Adb logcat -v time -s FlipSpot:V SpotifyAppRemote:D AndroidRuntime:E
}
