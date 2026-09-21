$ErrorActionPreference = 'Stop'
$folder = Join-Path $env:TEMP ("queue-desk-frame-smoke-{0}" -f [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$csv = Join-Path $folder 'process.csv'
$frames = Join-Path $folder 'gdi-frames.csv'
$html = Join-Path $folder 'process.html'
try {
    @'
Engine,ElapsedSeconds,Pid,Alive,CpuPercentOfMachine,WorkingSetMB,PrivateMemoryMB,Handles
gdi,0,101,True,,10,2,20
gdi,1,101,True,1,11,2,21
'@ | Set-Content -LiteralPath $csv -Encoding UTF8
    @'
Engine,Frame,ElapsedNs,IntervalNs,SceneNs,RenderNs,Commands,Width,Height
gdi,1,100000000,0,1000000,2000000,400,960,540
gdi,2,600000000,500000000,1500000,3000000,410,960,540
gdi,3,1100000000,500000000,1200000,4000000,420,960,540
'@ | Set-Content -LiteralPath $frames -Encoding UTF8
    & (Join-Path $PSScriptRoot 'report-queue-desk-frames.ps1') -CsvPath $csv -FrameDirectory $folder -OutputPath $html -CaptureSeconds 2
    if (-not (Test-Path -LiteralPath $html)) { throw 'HTML report missing.' }
    $body = Get-Content -LiteralPath $html -Raw
    foreach ($expected in @('Frame throughput', '2.0', 'Frame timing laboratory', 'Throughput timeline',
        'Longest completion gaps', '500.00', '4.00', '420', 'Process resources', '1% low')) {
        if (-not $body.Contains($expected)) { throw "Missing frame report fixture: $expected" }
    }
    Remove-Item -LiteralPath $frames -Force
    & (Join-Path $PSScriptRoot 'report-queue-desk-frames.ps1') -CsvPath $csv -FrameDirectory $folder -OutputPath $html -CaptureSeconds 2
    $body = Get-Content -LiteralPath $html -Raw
    if (-not $body.Contains('gdi: frame CSV missing') -or -not $body.Contains('FPS, frame timing and performance conclusions are unavailable')) {
        throw 'Missing frame data must be reported without fabricated numbers.'
    }
    Write-Host 'Frame-first HTML fixture passed: measured samples and missing-data behavior.'
} finally {
    Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction SilentlyContinue
}
