param(
    [switch]$Clean,
    [switch]$SkipTests,
    [ValidateSet('GDI','OpenGL','Direct3D9','Both','All')][string]$Backend = 'OpenGL',
    [ValidateRange(0, 120)][int]$WarmupSeconds = 3,
    [ValidateRange(2, 600)][int]$CaptureSeconds = 30
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build-windows.ps1') -SkipKotlin -Clean:$Clean -SkipTests:$SkipTests
if ($LASTEXITCODE -ne 0) { throw "Native build failed: $LASTEXITCODE" }
$build = Join-Path $root 'native/build'
$env:PATH = "$build;$(Join-Path $build 'Debug');$env:PATH"
function Find-Model([string]$name) {
    $path = Join-Path $build "$name.exe"
    if (-not (Test-Path -LiteralPath $path)) { $path = Join-Path $build "Debug/$name.exe" }
    if (-not (Test-Path -LiteralPath $path)) { throw "$name executable was not built." }
    return $path
}
$programs = @{
    gdi = (Find-Model 'model_benchmark_gdi')
    opengl = (Find-Model 'model_benchmark_opengl')
    direct3d9 = (Find-Model 'model_benchmark_d3d9')
}
Write-Host 'Shared real indexed mesh: 36 torus models, 96,768 triangles/frame.'
Write-Host 'GDI: CPU software triangles. OpenGL and D3D9: GPU triangles. API return != displayed FPS.'
if ($Backend -eq 'Both' -or $Backend -eq 'All') {
    $engines = if ($Backend -eq 'All') { @('opengl','direct3d9','gdi') } else { @('opengl','direct3d9') }
    $reportDir = Join-Path $env:TEMP ("queue-desk-model-{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), [guid]::NewGuid().ToString('N').Substring(0, 6))
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    $previousPath = $env:KD_MODEL_LOG_PATH
    $previousLimit = $env:KD_MODEL_LOG_MAX_SECONDS
    $env:KD_MODEL_LOG_MAX_SECONDS = [string]($WarmupSeconds + $CaptureSeconds)
    $processes = @{}
    try {
        foreach ($engine in $engines) {
            $env:KD_MODEL_LOG_PATH = Join-Path $reportDir "$engine-models.csv"
            $processes[$engine] = Start-Process -FilePath $programs[$engine] -WorkingDirectory $build -PassThru
            Write-Host "$engine PID: $($processes[$engine].Id)"
        }
    } finally {
        if ($null -eq $previousPath) { Remove-Item Env:KD_MODEL_LOG_PATH -ErrorAction SilentlyContinue }
        else { $env:KD_MODEL_LOG_PATH = $previousPath }
        if ($null -eq $previousLimit) { Remove-Item Env:KD_MODEL_LOG_MAX_SECONDS -ErrorAction SilentlyContinue }
        else { $env:KD_MODEL_LOG_MAX_SECONDS = $previousLimit }
    }
    Start-Sleep -Seconds 1
    $pids = @($engines | ForEach-Object { $processes[$_].Id })
    & (Join-Path $PSScriptRoot 'normalize-queue-desk-windows.ps1') -ProcessIds $pids -ClientWidth 1280 -ClientHeight 720
    Start-Sleep -Seconds ($WarmupSeconds + $CaptureSeconds + 3)
    foreach ($engine in $engines) {
        $processes[$engine].Refresh()
        if ($processes[$engine].HasExited) { Write-Warning "$engine exited with code $($processes[$engine].ExitCode); report will mark missing or incomplete data." }
    }
    & (Join-Path $PSScriptRoot 'report-model-benchmark.ps1') -FrameDirectory $reportDir -WarmupSeconds $WarmupSeconds -CaptureSeconds $CaptureSeconds -Engines $engines
    Write-Host "Model benchmark data: $reportDir"
    Write-Host "HTML: $(Join-Path $reportDir 'models.html')"
    Write-Host 'Independent clocks and resource contention: repeat each engine in isolation.'
    Write-Host 'Close model windows separately.'
} else {
    $selected = switch ($Backend) { 'GDI' { 'gdi' } 'Direct3D9' { 'direct3d9' } default { 'opengl' } }
    & $programs[$selected]
    if ($LASTEXITCODE -ne 0) { throw "$Backend model benchmark failed: $LASTEXITCODE" }
}
