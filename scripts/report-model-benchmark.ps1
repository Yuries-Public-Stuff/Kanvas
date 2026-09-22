param(
    [Parameter(Mandatory = $true)][string]$FrameDirectory,
    [ValidateRange(0, 120)][int]$WarmupSeconds = 3,
    [ValidateRange(2, 600)][int]$CaptureSeconds = 30,
    [ValidateSet('gdi','opengl','direct3d9')][string[]]$Engines = @('opengl','direct3d9'),
    [string]$OutputPath = ''
)
$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
if (-not $OutputPath) { $OutputPath = Join-Path $FrameDirectory 'models.html' }
function F([double]$n, [string]$format = '0.00') { $n.ToString($format, $culture) }
function E([string]$s) { [Net.WebUtility]::HtmlEncode($s) }
function Stat([double[]]$a) {
    if ($a.Count -eq 0) { return $null }
    [array]::Sort($a)
    $sum = 0.0
    foreach ($n in $a) { $sum += $n }
    $mean = $sum / $a.Count
    $variance = 0.0
    foreach ($n in $a) { $variance += ($n - $mean) * ($n - $mean) }
    $p50 = [math]::Max(0, [int][math]::Ceiling($a.Count * .50) - 1)
    $p95 = [math]::Max(0, [int][math]::Ceiling($a.Count * .95) - 1)
    $p99 = [math]::Max(0, [int][math]::Ceiling($a.Count * .99) - 1)
    return [pscustomobject]@{ Mean=$mean; Min=$a[0]; P50=$a[$p50]; P95=$a[$p95]; P99=$a[$p99]; Max=$a[-1]; SD=[math]::Sqrt($variance / $a.Count) }
}
$colors = @{ gdi='#f5b86e'; opengl='#39ddbc'; direct3d9='#9baeff' }
$labels = @{ gdi='GDI / CPU software'; opengl='OpenGL / GPU'; direct3d9='Direct3D 9 / GPU' }
$required = 'Renderer,Workload,Frame,ElapsedNs,IntervalNs,CpuCallNs,Triangles,Instances,Width,Height'
$startNs = [double]$WarmupSeconds * 1000000000.0
$endNs = [double]($WarmupSeconds + $CaptureSeconds) * 1000000000.0
$warnings = New-Object 'System.Collections.Generic.List[string]'
$cards = New-Object 'System.Collections.Generic.List[string]'
$rows = New-Object 'System.Collections.Generic.List[string]'
$charts = New-Object 'System.Collections.Generic.List[string]'
$stalls = New-Object 'System.Collections.Generic.List[string]'
$sizes = @{}
$valid = 0
foreach ($engine in $Engines) {
    $label = $labels[$engine]
    $path = Join-Path $FrameDirectory "$engine-models.csv"
    if (-not (Test-Path -LiteralPath $path)) {
        $warnings.Add("$label: source CSV missing: $path")
        $cards.Add('<article class="card fail"><span>' + $label + '</span><strong>NO DATA</strong></article>')
        continue
    }
    $header = (Get-Content -LiteralPath $path -TotalCount 1).TrimStart([char]0xFEFF)
    if ($header -ne $required) {
        $warnings.Add("$label: incompatible model CSV header; expected the ten-column dedicated mesh schema.")
        $cards.Add('<article class="card fail"><span>' + $label + '</span><strong>INVALID CSV</strong></article>')
        continue
    }
    $frames = New-Object 'System.Collections.Generic.List[object]'
    $previousFrame = [uint64]0
    $previousTime = [uint64]0
    $invalid = 0
    foreach ($r in @(Import-Csv -LiteralPath $path)) {
        if ($r.Renderer -ne $engine -or $r.Workload -ne 'torus-56x24-36' -or
            $r.Frame -notmatch '^\d+$' -or $r.ElapsedNs -notmatch '^\d+$' -or
            $r.IntervalNs -notmatch '^\d+$' -or $r.CpuCallNs -notmatch '^\d+$' -or
            $r.Triangles -notmatch '^\d+$' -or $r.Instances -notmatch '^\d+$' -or
            $r.Width -notmatch '^\d+$' -or $r.Height -notmatch '^\d+$') { $invalid++; continue }
        try {
            $frame = [uint64]$r.Frame
            $time = [uint64]$r.ElapsedNs
            $interval = [uint64]$r.IntervalNs
            $cpu = [uint64]$r.CpuCallNs
            $w = [uint32]$r.Width
            $h = [uint32]$r.Height
            $bad = $w -eq 0 -or $h -eq 0 -or $cpu -gt $time -or
                [uint32]$r.Triangles -ne 96768 -or [uint32]$r.Instances -ne 36
            if ($previousFrame -eq 0) { $bad = $bad -or $frame -ne 1 -or $interval -ne 0 }
            else { $bad = $bad -or $frame -ne $previousFrame + 1 -or $time -le $previousTime -or $interval -ne ($time - $previousTime) }
            if ($bad) { $invalid++; continue }
            $previousFrame = $frame
            $previousTime = $time
            if ([double]$time -ge $startNs -and [double]$time -le $endNs) {
                $frames.Add([pscustomobject]@{ Time=[double]$time; Cpu=[double]$cpu; Number=$frame; Width=$w; Height=$h })
            }
        } catch { $invalid++ }
    }
    if ($invalid) { $warnings.Add("$label: $invalid malformed/nonmonotonic/wrong-workload records.") }
    if ($frames.Count -lt 2) {
        $warnings.Add("$label: fewer than two completed frames in the capture window; FPS is unavailable.")
        $cards.Add('<article class="card fail"><span>' + $label + '</span><strong>INSUFFICIENT</strong></article>')
        continue
    }
    $valid++
    $viewports = @($frames | ForEach-Object { "$($_.Width)x$($_.Height)" } | Select-Object -Unique)
    $sizes[$engine] = $viewports
    if ($viewports.Count -ne 1) { $warnings.Add("$label: changing viewport(s): $($viewports -join ', ').") }
    $intervals = New-Object 'System.Collections.Generic.List[double]'
    $calls = New-Object 'System.Collections.Generic.List[double]'
    $events = New-Object 'System.Collections.Generic.List[object]'
    foreach ($frame in $frames) { $calls.Add($frame.Cpu / 1000000.0) }
    for ($i = 1; $i -lt $frames.Count; ++$i) {
        $ms = ($frames[$i].Time - $frames[$i-1].Time) / 1000000.0
        $intervals.Add($ms)
        $events.Add([pscustomobject]@{ Ms=$ms; At=$frames[$i].Time / 1000000000.0; Frame=$frames[$i].Number })
    }
    $duration = ($frames[$frames.Count-1].Time - $frames[0].Time) / 1000000000.0
    $fps = ($frames.Count - 1) / $duration
    $intervalStat = Stat ([double[]]$intervals.ToArray())
    $callStat = Stat ([double[]]$calls.ToArray())
    $worst = [double[]]$intervals.ToArray()
    [array]::Sort($worst)
    [array]::Reverse($worst)
    $worstCount = [math]::Max(1, [int][math]::Ceiling($worst.Count * .01))
    $worstSum = 0.0
    for ($i = 0; $i -lt $worstCount; ++$i) { $worstSum += $worst[$i] }
    $low = 1000.0 / ($worstSum / $worstCount)
    $over16 = @($intervals | Where-Object { $_ -gt 16.667 }).Count
    $over33 = @($intervals | Where-Object { $_ -gt 33.333 }).Count
    $over50 = @($intervals | Where-Object { $_ -gt 50.0 }).Count
    $over100 = @($intervals | Where-Object { $_ -gt 100.0 }).Count
    if ($duration / $CaptureSeconds -lt .90) { $warnings.Add("$label: only $(F (100.0 * $duration / $CaptureSeconds) '0.0')% of requested capture covered by retained completion timestamps.") }
    $cards.Add('<article class="card" style="--stripe:' + $colors[$engine] + '"><span>' + $label + '</span><strong>' + (F $fps '0.0') + '<small> calls/s</small></strong><p>' + $frames.Count + ' successful frames · ' + (F $duration) + ' s</p><div class="metric"><span>Interval P95</span><b>' + (F $intervalStat.P95) + ' ms</b><span>Interval P99</span><b>' + (F $intervalStat.P99) + ' ms</b><span>CPU call P95</span><b>' + (F $callStat.P95) + ' ms</b><span>1% low*</span><b>' + (F $low '0.0') + ' calls/s</b></div></article>')
    $rows.Add('<tr><th>' + $label + '</th><td>' + $frames.Count + '</td><td>' + (F $fps '0.0') + '</td><td>' + (F $intervalStat.Mean) + '</td><td>' + (F $intervalStat.Min) + '</td><td>' + (F $intervalStat.P50) + '</td><td>' + (F $intervalStat.P95) + '</td><td>' + (F $intervalStat.P99) + '</td><td>' + (F $intervalStat.Max) + '</td><td>' + (F $intervalStat.SD) + '</td><td>' + (F $low '0.0') + '</td><td>' + "$over16 / $over33 / $over50 / $over100" + '</td><td>' + (F $callStat.Mean) + '</td><td>' + (F $callStat.P50) + '</td><td>' + (F $callStat.P95) + '</td><td>' + (F $callStat.P99) + '</td><td>' + (F $callStat.Max) + '</td><td>' + (E ($viewports -join ', ')) + '</td></tr>')
    $top = @($events | Sort-Object Ms -Descending | Select-Object -First 8)
    $stalls.Add('<article class="panel"><h3>' + $label + ' · worst intervals</h3><table><thead><tr><th>Frame</th><th>Elapsed</th><th>Interval</th></tr></thead><tbody>' + ((@($top | ForEach-Object { '<tr><td>' + $_.Frame + '</td><td>' + (F $_.At) + ' s</td><td>' + (F $_.Ms) + ' ms</td></tr>' })) -join '') + '</tbody></table></article>')
    $ceiling = [math]::Max(16.667, $intervalStat.P99) * 1.4
    $step = [math]::Max(1, [int][math]::Ceiling($events.Count / 700.0))
    $a = New-Object 'System.Collections.Generic.List[string]'
    $b = New-Object 'System.Collections.Generic.List[string]'
    for ($i = 0; $i -lt $events.Count; $i += $step) {
        $x = 40.0 + 720.0 * $i / [math]::Max(1, $events.Count-1)
        $iy = 170.0 - 140.0 * [math]::Min($ceiling, $events[$i].Ms) / $ceiling
        $cy = 170.0 - 140.0 * [math]::Min($ceiling, $calls[$i+1]) / $ceiling
        $a.Add((F $x '0.0') + ',' + (F $iy '0.0'))
        $b.Add((F $x '0.0') + ',' + (F $cy '0.0'))
    }
    $threshold = 170.0 - 140.0 * 16.667 / $ceiling
    $charts.Add('<article class="panel"><h3>' + $label + ' · frame time</h3><p>Teal: completion interval; purple: synchronous call; dotted: 16.67 ms. Values above ' + (F $ceiling) + ' ms clipped only in graph; precise maxima remain in the table.</p><svg viewBox="0 0 800 200" role="img" aria-label="Measured model frame time"><path d="M40 24 V170 H760" fill="none" stroke="#64748b"/><path d="M40 ' + (F $threshold '0.0') + ' H760" stroke="#eebd75" stroke-dasharray="4 5"/><text x="2" y="30" fill="#a8b9d3" font-size="11">' + (F $ceiling '0.0') + '</text><polyline fill="none" stroke="#42e6cc" stroke-width="1.6" points="' + ($a -join ' ') + '"/><polyline fill="none" stroke="#ba9cff" stroke-width="1.6" points="' + ($b -join ' ') + '"/></svg><div class="source">' + (E ([IO.Path]::GetFullPath($path))) + '</div></article>')
}
if ($valid -lt $Engines.Count) { $warnings.Add('INCOMPLETE: one or more requested model renderers have no usable data. Missing data is not zero FPS.') }
$uniqueViewport = @($sizes.Values | ForEach-Object { $_ -join '|' } | Select-Object -Unique)
if ($uniqueViewport.Count -gt 1) { $warnings.Add('FAIRNESS: different viewport dimensions; this is not a controlled same-resolution comparison.') }
$notice = if ($warnings.Count) {
    '<section class="notice"><h2>Capture integrity / warnings</h2><ul>' + ((@($warnings | ForEach-Object { '<li>' + (E $_) + '</li>' })) -join '') + '</ul></section>'
} else { '<section class="notice good"><h2>Capture integrity</h2><p>All requested logs are valid and viewport sizes agree. GPU scheduling and presentation modes may still differ.</p></section>' }
$table = if ($rows.Count) {
    '<div class="scroll"><table><thead><tr><th>Engine</th><th>Frames</th><th>Calls/s</th><th>Interval avg</th><th>Min</th><th>P50</th><th>P95</th><th>P99</th><th>Max</th><th>SD</th><th>1% low*</th><th>&gt;16/33/50/100 ms</th><th>CPU call avg</th><th>Call P50</th><th>Call P95</th><th>Call P99</th><th>Call max</th><th>Viewport</th></tr></thead><tbody>' + ($rows -join '') + '</tbody></table></div>'
} else { '<p>No valid per-frame statistics available.</p>' }
$html = @'
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Queue Desk | Real model benchmark</title><style>
:root{font-family:Segoe UI,system-ui,sans-serif;background:#090e18;color:#e6ecf7;font-variant-numeric:tabular-nums}*{box-sizing:border-box}body{max-width:1500px;margin:auto;padding:32px}h1{font-size:clamp(34px,5vw,64px);margin:9px 0;letter-spacing:-.05em}h2{margin:32px 0 15px;font-size:22px}h3{font-size:15px;margin:0 0 14px}p,.source{color:#a8b9d3;line-height:1.65}.hero{padding:33px;border:1px solid #2c445e;border-radius:22px;background:radial-gradient(circle at 72% 0,#204c4a,transparent 47%),linear-gradient(110deg,#152d44,#111624)}.hero p{max-width:810px}.eyebrow,.card span{font-size:11px;text-transform:uppercase;letter-spacing:.13em;color:#9cb8d8;font-weight:750}.tags{display:flex;flex-wrap:wrap;gap:9px;margin-top:22px}.tags span{background:#263a51;color:#dae7f5;border-radius:24px;padding:7px 12px;font-size:12px}.cards,.charts{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,380px),1fr));gap:17px}.cards{margin-top:20px}.card,.panel{border:1px solid #304058;background:#121d2b;border-radius:15px;padding:22px}.card{border-top:4px solid var(--stripe,#9baeff)}.card.fail{--stripe:#ef8181}.card strong{display:block;margin:15px 0 3px;font-size:42px;letter-spacing:-.05em}.card strong small{font-size:13px;font-weight:500;letter-spacing:0;color:#a8b9d3}.metric{display:grid;grid-template-columns:1fr auto;gap:9px;font-size:13px;padding-top:15px;margin-top:15px;border-top:1px solid #304058}.metric span{color:#9cb0ce}.scroll{overflow-x:auto;border:1px solid #304058;border-radius:14px;background:#121d2b}.scroll table{min-width:1720px}table{width:100%;border-collapse:collapse;font-size:12px}th,td{text-align:left;padding:11px;white-space:nowrap;border-bottom:1px solid #304058}thead th{background:#192a40;color:#b9cde6}.panel svg{width:100%;height:auto}.notice{margin-top:24px;background:#35271a;border:1px solid #a87b4b;border-left:4px solid #ffc173;border-radius:12px;padding:16px 25px}.notice h2{margin:0 0 10px}.notice li{margin:8px 0;line-height:1.6}.notice.good{background:#14322e;border-color:#45987d}.source{overflow-wrap:anywhere;font-size:11px}footer{border-top:1px solid #304058;margin-top:35px;padding:20px 0;font-size:12px;color:#8298b5}@media(max-width:700px){body{padding:14px}.hero{padding:20px}}
</style></head><body>
'@
$html += '<header class="hero"><div class="eyebrow">Queue Desk / indexed geometry / local report</div><h1>Real model benchmark.</h1><p>Identical indexed torus geometry in the CPU software rasterizer (GDI), OpenGL and Direct3D 9. Depth-tested triangles with per-model rotation and lighting. These distinct engines share geometry but not identical GPU execution or lighting.</p><div class="tags"><span>96,768 triangles per frame</span><span>36 rotating instances</span><span>1,344 unique vertices per model</span><span>Warm-up ' + $WarmupSeconds + ' s</span><span>Capture ' + $CaptureSeconds + ' s</span></div></header>'
$html += '<h2>Completed model frames / second</h2><div class="cards">' + ($cards -join '') + '</div>' + $notice
$html += '<h2>Frame-time distributions · milliseconds unless noted</h2>' + $table + '<p>*1% low is 1000 divided by the mean of the slowest ceil(1%) adjacent completion intervals. Percentiles use nearest rank and intervals are recalculated within the retained capture after warm-up.</p>'
$html += '<h2>Frame-time traces</h2><div class="charts">' + ($charts -join '') + '</div><h2>Worst captured stalls</h2><div class="charts">' + ($stalls -join '') + '</div>'
$html += '<h2>Provenance and measurement limits</h2><article class="panel"><p>Analyze per-process elapsed seconds [' + $WarmupSeconds + ', ' + ($WarmupSeconds + $CaptureSeconds) + '] independently; process clocks and animation phases are not synchronized. GDI records a completed software raster + BitBlt; OpenGL records SwapBuffers; Direct3D 9 records Present. These timings are NOT confirmed displayed refreshes, GPU timestamp duration, GPU power, VRAM or input latency. CPU API call timing includes blocking and logging overhead can affect following intervals. Concurrent windows compete for GPU/CPU; run isolated trials before performance conclusions. The four-renderer 2D UI comparison is a separate workload and is not merged here.</p><p>Source directory: ' + (E ([IO.Path]::GetFullPath($FrameDirectory))) + '</p></article>'
$html += '<footer>Offline single-file HTML · no CDN, analytics or network request · generated ' + (E (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')) + ' · no GitHub Actions.</footer></body></html>'
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), $html, (New-Object Text.UTF8Encoding $false))
Write-Host "Model HTML report: $([IO.Path]::GetFullPath($OutputPath)) ($valid / $($Engines.Count) engines; $($warnings.Count) warnings)"
