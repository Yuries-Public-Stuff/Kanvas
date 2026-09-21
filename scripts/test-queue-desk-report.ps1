$ErrorActionPreference = 'Stop'
$folder = Join-Path $env:TEMP ("queue-desk-report-smoke-{0}" -f [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$csv = Join-Path $folder 'process.csv'
$html = Join-Path $folder 'process.html'
try {
    @'
Engine,ElapsedSeconds,Pid,Alive,CpuPercentOfMachine,WorkingSetMB,PrivateMemoryMB,Handles
gdi,0,101,True,,10,2,20
vulkan,0,102,True,,40,35,120
opengl,0,103,True,,25,20,80
direct3d9,0,104,True,,30,27,90
gdi,1,101,True,1,11,2,21
vulkan,1,102,True,5,42,36,121
opengl,1,103,True,3,26,21,82
direct3d9,1,104,True,4,31,28,91
'@ | Set-Content -LiteralPath $csv -Encoding UTF8
    foreach ($engine in @('gdi', 'vulkan', 'opengl', 'direct3d9')) {
        $frameCsv = Join-Path $folder "$engine-frames.csv"
        @"
Engine,Frame,ElapsedNs,IntervalNs,SceneNs,RenderNs,Commands,Width,Height
$engine,1,1000000000,0,2000000,1000000,900,960,540
$engine,2,1010000000,10000000,2000000,2000000,901,960,540
$engine,3,1030000000,20000000,3000000,4000000,902,960,540
$engine,4,1070000000,40000000,5000000,8000000,903,960,540
"@ | Set-Content -LiteralPath $frameCsv -Encoding UTF8
    }
    $report = Join-Path $PSScriptRoot 'report-queue-desk-frames.ps1'
    & $report -CsvPath $csv -FrameDirectory $folder -OutputPath $html -WarmupSeconds 1 -CaptureSeconds 0.05
    if (-not (Test-Path -LiteralPath $html)) { throw 'HTML missing.' }
    $body = Get-Content -LiteralPath $html -Raw
    foreach ($expected in @('<!doctype html>', 'Frame throughput', 'Frame timing laboratory',
        'Throughput timeline', 'Longest completion gaps', 'Capture provenance', 'DIRECT3D 9',
        'Calls/s', 'Interval P99', 'Call P95', '1% low', 'Working set (MB)',
        'CPU share of machine', 'Raw sample rows (8)', '66.67', '20.00', '902')) {
        if (-not $body.Contains($expected)) { throw "Missing expected report content: $expected" }
    }
    if ($body.Contains('<td>903</td>')) { throw 'Frames outside capture window were counted.' }
    if ($body.Contains('FAIRNESS: renderer client viewports differ')) { throw 'Matching viewports falsely marked mismatched.' }

    $vulkan = Join-Path $folder 'vulkan-frames.csv'
    (Get-Content -LiteralPath $vulkan -Raw).Replace(',902,960,540', ',902,960,541') |
        Set-Content -LiteralPath $vulkan -Encoding UTF8
    & $report -CsvPath $csv -FrameDirectory $folder -OutputPath $html -WarmupSeconds 1 -CaptureSeconds 0.05
    $body = Get-Content -LiteralPath $html -Raw
    if (-not $body.Contains('FAIRNESS: renderer client viewports differ')) { throw 'Viewport mismatch warning missing.' }
    Remove-Item -LiteralPath (Join-Path $folder 'opengl-frames.csv')
    & $report -CsvPath $csv -FrameDirectory $folder -OutputPath $html -WarmupSeconds 1 -CaptureSeconds 0.05
    $body = Get-Content -LiteralPath $html -Raw
    if (-not $body.Contains('opengl: frame CSV missing')) { throw 'Missing backend warning missing.' }
    Write-Host 'Queue Desk report smoke passed: capture window, four backends, viewport mismatch and missing data.'
} finally {
    Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction SilentlyContinue
}
