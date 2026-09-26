param(
    [Parameter(Mandatory=$true, Position=0)][string]$WakeDir,
    [ValidateSet('auto','vulkan','d3d9','opengl','gdi')][string]$Backend = 'auto',
    [switch]$InstallTools,
    [switch]$NoClean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$WakeDir = [IO.Path]::GetFullPath($WakeDir)

$runLogDir = Join-Path $root 'build\run-logs'
New-Item -ItemType Directory -Path $runLogDir -Force | Out-Null
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runLog = Join-Path $runLogDir "wake-windows-$runStamp.log"
Start-Transcript -Path $runLog -Force | Out-Null

if (-not (Test-Path (Join-Path $WakeDir '.git'))) {
    throw "Wake checkout not found: $WakeDir"
}

Write-Host '==> Windows prerequisite check'
$setupArgs = @{
    SkipGradleCheck = $true
}
if ($InstallTools) { $setupArgs.InstallTools = $true }
& (Join-Path $PSScriptRoot 'setup-windows.ps1') @setupArgs
if ($LASTEXITCODE -ne 0) {
    throw 'Windows prerequisite check failed.'
}

Write-Host ''
Write-Host '==> Windows Kotlin Display + Wake strict parity launch'
$parityArgs = @{
    WakeDir = $WakeDir
    Backend = $Backend
    LaunchStrict = $true
}
if ($NoClean) { $parityArgs.SkipClean = $true }

try {
    & (Join-Path $PSScriptRoot 'test-wake-parity-windows.ps1') @parityArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Wake strict takeover failed with exit code $LASTEXITCODE"
    }

    Write-Host ''
    Write-Host 'Wake Windows strict takeover completed.'
    Write-Host (Join-Path $root 'build\parity\wake-parity-windows.md')
} finally {
    Write-Host ''
    Write-Host "Terminal transcript: $runLog"
    try { Stop-Transcript | Out-Null } catch {}
}
