$ErrorActionPreference = 'Stop'
$folder = Join-Path $env:TEMP ("queue-desk-mesh-report-test-{0}" -f [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$engines = @('gdi','opengl','direct3d9')
try {
    foreach ($name in $engines) {
        $path = Join-Path $folder "$name-models.csv"
        @"
Renderer,Workload,Frame,ElapsedNs,IntervalNs,CpuCallNs,Triangles,Instances,Width,Height
$name,torus-56x24-36,1,100000000,0,1000000,96768,36,960,540
$name,torus-56x24-36,2,110000000,10000000,2000000,96768,36,960,540
$name,torus-56x24-36,3,130000000,20000000,4000000,96768,36,960,540
$name,torus-56x24-36,4,160000000,30000000,8000000,96768,36,960,540
"@ | Set-Content -LiteralPath $path -Encoding UTF8
    }
    $report = Join-Path $folder 'models.html'
    & (Join-Path $PSScriptRoot 'report-model-benchmark.ps1') -FrameDirectory $folder -WarmupSeconds 0 -CaptureSeconds 2 -Engines $engines -OutputPath $report
    if (-not (Test-Path -LiteralPath $report)) { throw 'Model HTML missing.' }
    $html = Get-Content -LiteralPath $report -Raw
    foreach ($expected in @('Real model benchmark.', 'GDI / CPU software', 'OpenGL / GPU', 'Direct3D 9 / GPU', '96,768 triangles', '50.0', '30.00', '8.00', '1% low', '960x540', 'Frame-time traces')) {
        if (-not $html.Contains($expected)) { throw "Expected model statistic missing: $expected" }
    }
    if ($html.Contains('FAIRNESS:') -or $html.Contains('INCOMPLETE:')) { throw 'Three complete, same-viewport fixtures incorrectly rejected.' }
    $d3d = Join-Path $folder 'direct3d9-models.csv'
    $changed = (Get-Content -LiteralPath $d3d -Raw).Replace(',960,540', ',800,600')
    Set-Content -LiteralPath $d3d -Value $changed -Encoding UTF8
    & (Join-Path $PSScriptRoot 'report-model-benchmark.ps1') -FrameDirectory $folder -WarmupSeconds 0 -CaptureSeconds 2 -Engines $engines -OutputPath $report
    if (-not (Get-Content -LiteralPath $report -Raw).Contains('FAIRNESS:')) { throw 'Mismatched viewports not flagged.' }
    Remove-Item -LiteralPath $d3d -Force
    & (Join-Path $PSScriptRoot 'report-model-benchmark.ps1') -FrameDirectory $folder -WarmupSeconds 0 -CaptureSeconds 2 -Engines $engines -OutputPath $report
    $html = Get-Content -LiteralPath $report -Raw
    if (-not $html.Contains('NO DATA') -or -not $html.Contains('INCOMPLETE:')) {
        throw 'Missing renderer must show unavailable, not synthetic FPS.'
    }
    Write-Host 'Three-engine model report fixtures passed: frame metrics, viewport fairness, absent-backend handling.'
} finally {
    Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction SilentlyContinue
}
