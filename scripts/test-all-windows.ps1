param(
    [string]$GradleLauncher = '',
    [switch]$NoClean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$arguments = @()
if (-not $NoClean) {
    $arguments += '-Clean'
}
if ($GradleLauncher) {
    $arguments += '-GradleLauncher'
    $arguments += $GradleLauncher
}

Write-Host '==> Kotlin Display full Windows unit/regression suite'
& (Join-Path $PSScriptRoot 'build-windows.ps1') @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Full Windows test suite failed with exit code $LASTEXITCODE"
}

Write-Host ''
Write-Host 'TEST PASS'
Write-Host '  Native C/Win32/backend tests : PASS'
Write-Host '  Renderer JVM tests           : PASS'
Write-Host '  Compose bridge tests         : PASS'
Write-Host '  Integration-agent tests      : PASS'
Write-Host '  Demo compile                 : PASS'
