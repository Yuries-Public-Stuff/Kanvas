$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = if ($env:KANVAS_GRADLE) { $env:KANVAS_GRADLE } else { "gradle" }

$Examples = @(
    "examples/kotlin-jvm-basic",
    "examples/compose-desktop-basic",
    "examples/compose-desktop-multimodule"
)

foreach ($Example in $Examples) {
    Write-Host ""
    Write-Host "==> Verifying $Example"
    & $Gradle -p (Join-Path $Root $Example) --no-daemon clean kanvasDoctor kanvasCompatibility kanvasBuild
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host ""
Write-Host "All Kanvas examples built successfully."
