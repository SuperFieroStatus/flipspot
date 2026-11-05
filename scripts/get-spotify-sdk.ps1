<#
.SYNOPSIS
  Downloads Spotify's App Remote SDK (.aar) into app/libs. Run once after cloning.

.DESCRIPTION
  FlipSpot uses Spotify App Remote 0.8.0, which Spotify publishes only as an .aar on GitHub
  (https://github.com/spotify/android-sdk, Apache-2.0; use is also subject to the Spotify Developer
  Terms). It isn't committed here; this script fetches the official release file and checks its
  SHA-256 against the one FlipSpot was built and tested with.

  macOS / Linux equivalent:
    curl -L -o app/libs/spotify-app-remote-release-0.8.0.aar \
      https://github.com/spotify/android-sdk/releases/download/v0.8.0-appremote_v2.1.0-auth/spotify-app-remote-release-0.8.0.aar
#>
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root = Split-Path -Parent $PSScriptRoot
$Name = 'spotify-app-remote-release-0.8.0.aar'
$Url = "https://github.com/spotify/android-sdk/releases/download/v0.8.0-appremote_v2.1.0-auth/$Name"
$Sha256 = 'B5A6DD880EAF01F63A871CBA9EF7AF77C341F8A94FFC8FDF2E9021F9A9D4C198'
$Dir = Join-Path $Root 'app\libs'
$Out = Join-Path $Dir $Name

if ((Test-Path $Out) -and ((Get-FileHash $Out -Algorithm SHA256).Hash -eq $Sha256)) {
    Write-Host "Already present: $Out"
    exit 0
}
New-Item -ItemType Directory -Force $Dir | Out-Null
Write-Host "Downloading $Url"
Invoke-WebRequest -Uri $Url -OutFile $Out -UseBasicParsing
$hash = (Get-FileHash $Out -Algorithm SHA256).Hash
if ($hash -ne $Sha256) {
    Remove-Item $Out
    Write-Host "ERROR: checksum mismatch ($hash); file removed." -ForegroundColor Red
    exit 1
}
Write-Host "OK: $Out (SHA-256 verified)"
