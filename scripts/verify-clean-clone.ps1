param(
    [string]$RepositoryUrl = "",
    [string]$Ref = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if (-not $RepositoryUrl) {
    $RepositoryUrl = (& git -C $Root remote get-url origin).Trim()
}

if (-not $Ref) {
    $Ref = (& git -C $Root rev-parse HEAD).Trim()
}

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("kanvas-clean-" + [guid]::NewGuid().ToString("N"))

try {
    Write-Host "Cloning $RepositoryUrl"
    & git clone --quiet $RepositoryUrl (Join-Path $TempRoot "Kanvas")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $Checkout = Join-Path $TempRoot "Kanvas"
    & git -C $Checkout checkout --quiet $Ref
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host "Verifying clean checkout at $Ref"
    & (Join-Path $Checkout "scripts\verify-examples.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    if (Test-Path $TempRoot) {
        Remove-Item -Recurse -Force $TempRoot
    }
}
