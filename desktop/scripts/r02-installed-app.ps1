# R02 re-run against the CURRENT build (silent per-user install -> launch -> uninstall).
#
# Deliberately conservative: it only touches the per-user install path
# ($env:LOCALAPPDATA\Programs\AIStudy) and the uninstaller it runs there, plus
# %APPDATA%\AIStudy which the installer is configured to keep.
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\r02-installed-app.ps1

$ErrorActionPreference = 'Stop'
$setup = Join-Path $PSScriptRoot '..\dist\AIStudy Setup 0.1.0.exe'
$installDir = Join-Path $env:LOCALAPPDATA 'Programs\AIStudy'
$appData = Join-Path $env:APPDATA 'AIStudy'

if (-not (Test-Path $setup)) { Write-Output "FAIL | installer missing: $setup"; exit 1 }
$fi = Get-Item $setup
Write-Output ("PASS | artifact | bytes={0} mtime={1}" -f $fi.Length, $fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))

# Uninstall anything left from an earlier pass, waiting for the directory to
# actually disappear: the NSIS uninstaller returns before it finishes deleting.
$uninstaller = Join-Path $installDir 'Uninstall AIStudy.exe'
if (Test-Path $uninstaller) {
    Start-Process -FilePath $uninstaller -ArgumentList '/S' -Wait
    $deadline = (Get-Date).AddSeconds(60)
    while ((Test-Path $installDir) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    Write-Output ("INFO | previous install removed | dirExists={0}" -f (Test-Path $installDir))
}

$p = Start-Process -FilePath $setup -ArgumentList '/S' -Wait -PassThru
Write-Output ("{0} | silent install | exit={1}" -f ($(if ($p.ExitCode -eq 0) { 'PASS' } else { 'FAIL' }), $p.ExitCode))

$exe = Join-Path $installDir 'AIStudy.exe'
if (-not (Test-Path $exe)) { Write-Output "FAIL | installed exe missing"; exit 1 }
$ver = (Get-Item $exe).VersionInfo
Write-Output ("PASS | installed exe | productVersion={0}" -f $ver.ProductVersion)

Start-Process -FilePath $exe | Out-Null
Start-Sleep -Seconds 8
$procs = @(Get-Process -Name 'AIStudy' -ErrorAction SilentlyContinue)
$titled = @($procs | Where-Object { $_.MainWindowTitle -ne '' } | ForEach-Object { $_.MainWindowTitle })
Write-Output ("{0} | launched | processes={1} windowTitles={2}" -f $(if ($procs.Count -gt 0 -and $titled.Count -gt 0) { 'PASS' } else { 'FAIL' }), $procs.Count, ($titled -join ';'))

# R03 needs the credentials typed into that window; nothing here can assert a
# login happened, so the process is simply closed and the row stays "not run".
$procs | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

Start-Process -FilePath (Join-Path $installDir 'Uninstall AIStudy.exe') -ArgumentList '/S' -Wait
$deadline = (Get-Date).AddSeconds(90)
while ((Test-Path $installDir) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
Write-Output ("{0} | uninstall removed install dir | exists={1}" -f $(if (-not (Test-Path $installDir)) { 'PASS' } else { 'FAIL' }), (Test-Path $installDir))

$regKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{*'
$stillListed = @(Get-ChildItem $regKey -ErrorAction SilentlyContinue | Where-Object { $_.GetValue('DisplayName') -eq 'AIStudy' })
Write-Output ("{0} | uninstall entry gone | listed={1}" -f $(if ($stillListed.Count -eq 0) { 'PASS' } else { 'FAIL' }), $stillListed.Count)
Write-Output ("INFO | app data kept by policy (deleteAppDataOnUninstall=false) | exists={0}" -f (Test-Path $appData))
