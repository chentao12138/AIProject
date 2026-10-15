# Install the current artifact and launch it with a CDP port so
# scripts\r03-installed-app.mjs can drive the real packaged window.
#
# R02's script deliberately closes the app at the end; this one leaves it
# running, so it does NOT uninstall afterwards — the driver needs a live
# %APPDATA% session. Run:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\r03-install-and-launch.ps1

$ErrorActionPreference = 'Stop'
$setup = Join-Path $PSScriptRoot '..\dist\AIStudy Setup 0.1.0.exe'
$installDir = Join-Path $env:LOCALAPPDATA 'Programs\AIStudy'
$cdp = 'http://127.0.0.1:9222'

if (-not (Test-Path $setup)) { Write-Output "FAIL | installer missing: $setup"; exit 1 }
$fi = Get-Item $setup
Write-Output ("INFO | artifact | bytes={0} mtime={1}" -f $fi.Length, $fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))

@(Get-Process -Name 'AIStudy' -ErrorAction SilentlyContinue) | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

$uninstaller = Join-Path $installDir 'Uninstall AIStudy.exe'
if (Test-Path $uninstaller) {
    Start-Process -FilePath $uninstaller -ArgumentList '/S' -Wait
    # The NSIS uninstaller returns before it finishes deleting.
    $deadline = (Get-Date).AddSeconds(60)
    while ((Test-Path $installDir) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    Write-Output ("INFO | previous install removed | dirExists={0}" -f (Test-Path $installDir))
}

$p = Start-Process -FilePath $setup -ArgumentList '/S' -Wait -PassThru
Write-Output ("{0} | silent install | exit={1}" -f ($(if ($p.ExitCode -eq 0) { 'PASS' } else { 'FAIL' }), $p.ExitCode))

$exe = Join-Path $installDir 'AIStudy.exe'
if (-not (Test-Path $exe)) { Write-Output "FAIL | installed exe missing"; exit 1 }

Start-Process -FilePath $exe -ArgumentList '--remote-debugging-port=9222' | Out-Null

$target = $null
$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline) {
    try {
        $list = Invoke-RestMethod -Uri "$cdp/json" -TimeoutSec 3
        $target = @($list | Where-Object { $_.type -eq 'page' })
        if ($target.Count -gt 0) { break }
    } catch { Start-Sleep -Milliseconds 800 }
    Start-Sleep -Milliseconds 800
}
if (-not $target) { Write-Output "FAIL | no CDP page target on $cdp"; exit 1 }
Write-Output ("PASS | cdp page target | url={0}" -f $target[0].url)
