<#
.SYNOPSIS
  Fallback sign-in: runs Spotify PKCE in your desktop browser and pushes token.json to the phone.

.DESCRIPTION
  One-time dashboard setup: add this extra Redirect URI to the FlipSpot app on
  developer.spotify.com (next to flipspot://callback):

      http://127.0.0.1:8888/callback

  Then run:  .\scripts\get-token.ps1
  The script opens the consent page, catches the redirect on 127.0.0.1:8888, exchanges the code,
  pushes token.json to /sdcard/Android/data/dev.mike.flipspot/files/, deletes the local copy, and
  restarts FlipSpot, which imports the file on launch and then deletes it.

  Also the quickest way to reset auth while testing.
#>
[CmdletBinding()]
param(
    [int]$Port = 8888,
    [string]$Serial,
    [switch]$NoPush   # keep token.json locally instead of pushing it
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Root = Split-Path -Parent $PSScriptRoot
$Package = 'dev.mike.flipspot'
$Redirect = "http://127.0.0.1:$Port/callback"
$Scopes = 'user-read-playback-state user-modify-playback-state user-read-currently-playing ' +
          'playlist-read-private playlist-read-collaborative user-library-read user-read-playback-position app-remote-control streaming'

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function B64Url([byte[]]$bytes) { [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_') }

# ---- client id from local.properties ----
$props = Join-Path $Root 'local.properties'
$line = if (Test-Path $props) { Get-Content $props | Where-Object { $_ -match '^\s*SPOTIFY_CLIENT_ID\s*=' } | Select-Object -First 1 }
if (-not $line) { Fail 'SPOTIFY_CLIENT_ID not found in local.properties' }
$ClientId = ($line -replace '^\s*SPOTIFY_CLIENT_ID\s*=\s*', '').Trim()

# ---- PKCE ----
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$buf = New-Object byte[] 64; $rng.GetBytes($buf); $Verifier = B64Url $buf
$sha = [Security.Cryptography.SHA256]::Create()
$Challenge = B64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($Verifier)))
$buf = New-Object byte[] 16; $rng.GetBytes($buf); $State = B64Url $buf

$q = @{
    client_id             = $ClientId
    response_type         = 'code'
    redirect_uri          = $Redirect
    code_challenge_method = 'S256'
    code_challenge        = $Challenge
    scope                 = $Scopes
    state                 = $State
}
$query = ($q.GetEnumerator() | ForEach-Object { "$($_.Key)=$([uri]::EscapeDataString($_.Value))" }) -join '&'
$AuthUrl = "https://accounts.spotify.com/authorize?$query"

# ---- one-shot loopback listener (TcpListener: no admin / urlacl needed) ----
$listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
try { $listener.Start() } catch { Fail "Port $Port is busy. Re-run with -Port <n> (and register that redirect URI)." }

Write-Host "Opening browser for Spotify consent..." -ForegroundColor Cyan
Start-Process $AuthUrl

$code = $null
try {
    while (-not $code) {
        $client = $listener.AcceptTcpClient()
        $stream = $client.GetStream()
        $reader = New-Object IO.StreamReader($stream)
        $requestLine = $reader.ReadLine()
        while (($h = $reader.ReadLine()) -ne $null -and $h -ne '') { }   # drain headers

        $page = 'Not the callback.'
        if ($requestLine -match '^GET (/callback\?[^ ]*) ') {
            $params = @{}
            foreach ($kv in ($Matches[1] -replace '^/callback\?', '').Split('&')) {
                $k, $v = $kv.Split('=', 2)
                $params[[uri]::UnescapeDataString($k)] = [uri]::UnescapeDataString(($v -replace '\+', ' '))
            }
            if ($params['state'] -ne $State) { $page = 'State mismatch; ignored.' }
            elseif ($params['error']) { $page = "Spotify returned: $($params['error'])"; $err = $params['error'] }
            else { $code = $params['code']; $page = 'FlipSpot: signed in. You can close this tab.' }
        }
        $body = [Text.Encoding]::UTF8.GetBytes("<html><body style='font-family:sans-serif'><h3>$page</h3></body></html>")
        $hdr = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n")
        $stream.Write($hdr, 0, $hdr.Length); $stream.Write($body, 0, $body.Length); $stream.Flush()
        $client.Close()
        if ($err) { Fail "Authorization failed: $err" }
    }
} finally { $listener.Stop() }

# ---- code -> tokens ----
Write-Host 'Exchanging code for tokens...' -ForegroundColor Cyan
$obtained = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$resp = Invoke-RestMethod -Method Post -Uri 'https://accounts.spotify.com/api/token' `
    -ContentType 'application/x-www-form-urlencoded' -Body @{
        grant_type    = 'authorization_code'
        code          = $code
        redirect_uri  = $Redirect
        client_id     = $ClientId
        code_verifier = $Verifier
    }
if (-not $resp.access_token) { Fail 'No access_token in response' }
$resp | Add-Member -NotePropertyName obtained_at_ms -NotePropertyValue $obtained
$tokenFile = Join-Path $Root 'token.json'   # gitignored
[IO.File]::WriteAllText($tokenFile, ($resp | ConvertTo-Json -Compress))
Write-Host "Got tokens (scope: $($resp.scope); refresh token: $([bool]$resp.refresh_token))" -ForegroundColor Green

if ($NoPush) { Write-Host "Kept $tokenFile (contains a refresh token; don't share it)."; exit 0 }

# ---- push to phone ----
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
$Adb = if ($adbCmd) { $adbCmd.Source } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
$AdbArgs = @(); if ($Serial) { $AdbArgs = @('-s', $Serial) }
$dest = "/sdcard/Android/data/$Package/files/token.json"
try {
    & $Adb @AdbArgs shell mkdir -p "/sdcard/Android/data/$Package/files"
    & $Adb @AdbArgs push $tokenFile $dest
    if ($LASTEXITCODE -ne 0) { Fail "adb push failed; token kept at $tokenFile" }
    Remove-Item $tokenFile
    & $Adb @AdbArgs shell am start -S -n "$Package/.HomeActivity" | Out-Null
    Write-Host 'Pushed and restarted FlipSpot; it imports and deletes the file on launch.' -ForegroundColor Green
    Write-Host 'Check: adb logcat -d -s FlipSpot | findstr Auth'
} catch {
    Fail "Push failed: $_  (token kept at $tokenFile)"
}
