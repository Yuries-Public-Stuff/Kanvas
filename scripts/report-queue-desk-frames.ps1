param(
    [Parameter(Mandatory = $true)][string]$CsvPath,
    [Parameter(Mandatory = $true)][string]$FrameDirectory,
    [string]$OutputPath = [IO.Path]::ChangeExtension($CsvPath, '.html'),
    [ValidateRange(0, 600)][double]$WarmupSeconds = 0,
    [ValidateRange(0, 600)][double]$CaptureSeconds = 0
)

$ErrorActionPreference = 'Stop'
$ci = [Globalization.CultureInfo]::InvariantCulture
function N([double]$value, [string]$format = '0.00') { $value.ToString($format, $ci) }
function H([string]$value) { [Net.WebUtility]::HtmlEncode($value) }
function P([double[]]$sorted, [double]$percent) {
    if ($sorted.Count -eq 0) { return [double]::NaN }
    $index = [math]::Max(0, [math]::Ceiling($sorted.Count * $percent / 100.0) - 1)
    return $sorted[[int]$index]
}
function Stats([double[]]$values) {
    if ($values.Count -eq 0) { return $null }
    [array]::Sort($values)
    $sum = 0.0
    foreach ($value in $values) { $sum += $value }
    $avg = $sum / $values.Count
    $squares = 0.0
    foreach ($value in $values) { $squares += [math]::Pow($value - $avg, 2) }
    return [pscustomobject]@{ Mean = $avg; Min = $values[0]; Max = $values[-1]
        P50 = (P $values 50); P95 = (P $values 95); P99 = (P $values 99)
        P999 = (P $values 99.9); Sd = [math]::Sqrt($squares / $values.Count) }
}
function Cell($value) {
    if ($null -eq $value -or ([double]$value).ToString() -eq 'NaN') { return 'n/a' }
    return N ([double]$value)
}

$process = @(Import-Csv -LiteralPath $CsvPath)
if ($process.Count -eq 0) { throw 'Process CSV has no rows.' }
$maxProcessSeconds = [double](($process | Measure-Object -Property ElapsedSeconds -Maximum).Maximum)
if ($CaptureSeconds -le 0) { $CaptureSeconds = $maxProcessSeconds }
$processEngines = @($process | Select-Object -ExpandProperty Engine -Unique)
$expected = @('gdi', 'vulkan', 'opengl', 'direct3d9') | Where-Object { $_ -in $processEngines }
$colors = @{ gdi = '#60a5fa'; vulkan = '#34d399'; opengl = '#fbbf24'; direct3d9 = '#c084fc' }
$names = @{ gdi = 'GDI'; vulkan = 'VULKAN'; opengl = 'OPENGL'; direct3d9 = 'DIRECT3D 9' }
$warnings = New-Object 'System.Collections.Generic.List[string]'
$cards = New-Object 'System.Collections.Generic.List[string]'
$rows = New-Object 'System.Collections.Generic.List[string]'
$plots = New-Object 'System.Collections.Generic.List[string]'
$anomalies = New-Object 'System.Collections.Generic.List[string]'
$viewportSet = @{}
$coverage = @{}
$totalRecords = 0
$sourceNames = New-Object 'System.Collections.Generic.List[string]'
$windowStart = $WarmupSeconds * 1000000000.0
$windowEnd = ($WarmupSeconds + $CaptureSeconds) * 1000000000.0

foreach ($engine in $expected) {
    $file = Join-Path $FrameDirectory "$engine-frames.csv"
    if (-not (Test-Path -LiteralPath $file)) {
        $warnings.Add("$engine`: frame CSV missing; no throughput or timing measurements available.")
        continue
    }
    $sourceNames.Add([IO.Path]::GetFileName($file))
    $raw = @(Import-Csv -LiteralPath $file)
    $valid = New-Object 'System.Collections.Generic.List[object]'
    $bad = 0
    $priorFrame = 0L
    $priorNs = -1.0
    $sizes = @{}
    foreach ($record in $raw) {
        $number = 0L; $elapsed = 0UL; $intervalNs = 0UL; $renderNs = 0UL; $sceneNs = 0UL
        $commands = 0; $width = 0; $height = 0
        $ok = $record.Engine -eq $engine -and
            [long]::TryParse([string]$record.Frame, [ref]$number) -and
            [ulong]::TryParse([string]$record.ElapsedNs, [ref]$elapsed) -and
            [ulong]::TryParse([string]$record.IntervalNs, [ref]$intervalNs) -and
            [ulong]::TryParse([string]$record.RenderNs, [ref]$renderNs) -and
            [ulong]::TryParse([string]$record.SceneNs, [ref]$sceneNs) -and
            [int]::TryParse([string]$record.Commands, [ref]$commands) -and
            [int]::TryParse([string]$record.Width, [ref]$width) -and
            [int]::TryParse([string]$record.Height, [ref]$height)
        if (-not $ok -or $number -le $priorFrame -or [double]$elapsed -le $priorNs -or
            $commands -lt 1 -or $commands -gt 4096 -or $width -le 0 -or $height -le 0) {
            $bad++
            continue
        }
        $priorFrame = $number; $priorNs = [double]$elapsed
        if ([double]$elapsed -lt $windowStart -or [double]$elapsed -gt $windowEnd) { continue }
        $valid.Add($record)
        $size = "$width x $height"
        $sizes[$size] = 1
    }
    if ($bad) { $warnings.Add("$engine`: $bad malformed, duplicate or nonmonotonic row(s) excluded.") }
    if ($valid.Count -lt 2) {
        $warnings.Add("$engine`: fewer than two frames in the selected capture window; no reliable rate.")
        continue
    }
    $totalRecords += $valid.Count
    $viewportSet[$engine] = @($sizes.Keys)
    if ($sizes.Count -ne 1) { $warnings.Add("$engine`: viewport changed during capture ($(@($sizes.Keys) -join ', ')).") }
    $timeStart = [double]$valid[0].ElapsedNs
    $timeEnd = [double]$valid[$valid.Count - 1].ElapsedNs
    $duration = ($timeEnd - $timeStart) / 1e9
    $coverage[$engine] = $duration
    $rate = if ($duration -gt 0) { ($valid.Count - 1) / $duration } else { 0.0 }
    $render = [double[]]@($valid | ForEach-Object { [double]$_.RenderNs / 1e6 })
    $scene = [double[]]@($valid | ForEach-Object { [double]$_.SceneNs / 1e6 })
    $interval = [double[]]@($valid | Where-Object { [double]$_.IntervalNs -gt 0 } | ForEach-Object { [double]$_.IntervalNs / 1e6 })
    $commandValues = @($valid | ForEach-Object { [int]$_.Commands })
    $rs = Stats $render; $us = Stats $scene; $is = Stats $interval
    $meanCommands = ($commandValues | Measure-Object -Average).Average
    $maxCommands = ($commandValues | Measure-Object -Maximum).Maximum
    $late16 = @($interval | Where-Object { $_ -gt 16.667 }).Count
    $late33 = @($interval | Where-Object { $_ -gt 33.333 }).Count
    $late50 = @($interval | Where-Object { $_ -gt 50 }).Count
    $late100 = @($interval | Where-Object { $_ -gt 100 }).Count
    $latePct = if ($interval.Count) { 100.0 * $late16 / $interval.Count } else { 0 }
    $low1 = if ($is -and $is.P99 -gt 0) { 1000.0 / $is.P99 } else { [double]::NaN }
    $low01 = if ($interval.Count -ge 1000 -and $is.P999 -gt 0) { 1000.0 / $is.P999 } else { [double]::NaN }
    $label = $names[$engine]; $color = $colors[$engine]
    $cards.Add('<article class="metric" style="--accent:' + $color + '"><div class="eyebrow">' + $label + ' / completed calls</div><div class="big">' + (N $rate '0.0') + '<small> calls/s</small></div><div class="small">' + $valid.Count + ' frames · ' + (N $duration) + ' s · ' + (H ($viewportSet[$engine] -join ', ')) + '</div><div class="statgrid"><span>Interval P95 / P99</span><b>' + (Cell $is.P95) + ' / ' + (Cell $is.P99) + ' ms</b><span>Call P95 / P99</span><b>' + (Cell $rs.P95) + ' / ' + (Cell $rs.P99) + ' ms</b><span>1% low (interval)</span><b>' + (Cell $low1) + ' calls/s</b><span>Over 16.67 ms</span><b>' + (N $latePct '0.0') + '%</b></div></article>')
    $rows.Add('<tr><td>' + $label + '</td><td>' + $valid.Count + '</td><td>' + (N $rate) + '</td><td>' + (Cell $low1) + '</td><td>' + (Cell $low01) + '</td><td>' + (Cell $is.Mean) + '</td><td>' + (Cell $is.Min) + '</td><td>' + (Cell $is.P50) + '</td><td>' + (Cell $is.P95) + '</td><td>' + (Cell $is.P99) + '</td><td>' + (Cell $is.Max) + '</td><td>' + (Cell $is.Sd) + '</td><td>' + $late16 + ' / ' + $late33 + ' / ' + $late50 + ' / ' + $late100 + '</td><td>' + (Cell $rs.Mean) + '</td><td>' + (Cell $rs.P50) + '</td><td>' + (Cell $rs.P95) + '</td><td>' + (Cell $rs.P99) + '</td><td>' + (Cell $rs.Max) + '</td><td>' + (Cell $us.Mean) + '</td><td>' + (Cell $us.P95) + '</td><td>' + (N $meanCommands) + '</td><td>' + $maxCommands + '</td><td>' + (H ($viewportSet[$engine] -join ', ')) + '</td></tr>')
    $worst = @($valid | Sort-Object { [double]$_.IntervalNs } -Descending | Select-Object -First 5)
    foreach ($event in $worst) {
        $anomalies.Add('<tr><td>' + $label + '</td><td>' + (H ([string]$event.Frame)) + '</td><td>' + (N ([double]$event.ElapsedNs / 1e9)) + '</td><td>' + (N ([double]$event.IntervalNs / 1e6)) + '</td><td>' + (N ([double]$event.RenderNs / 1e6)) + '</td><td>' + (N ([double]$event.SceneNs / 1e6)) + '</td></tr>')
    }
    $bins = @{}
    foreach ($record in $valid) {
        $second = [int][math]::Floor(([double]$record.ElapsedNs - $windowStart) / 1e9)
        if (-not $bins.ContainsKey($second)) { $bins[$second] = [pscustomobject]@{ Count = 0; MaxInterval = 0.0; MaxCall = 0.0 } }
        $bin = $bins[$second]; $bin.Count++
        $bin.MaxInterval = [math]::Max($bin.MaxInterval, [double]$record.IntervalNs / 1e6)
        $bin.MaxCall = [math]::Max($bin.MaxCall, [double]$record.RenderNs / 1e6)
    }
    $bars = New-Object 'System.Collections.Generic.List[string]'
    $maxBin = [math]::Max(1, ($bins.Values | Measure-Object -Property Count -Maximum).Maximum)
    foreach ($second in @($bins.Keys | Sort-Object)) {
        $x = 34 + 760.0 * [double]$second / [math]::Max(1, $CaptureSeconds)
        $h = 145.0 * $bins[$second].Count / $maxBin
        $bars.Add('<rect x="' + (N $x '0.0') + '" y="' + (N (175 - $h) '0.0') + '" width="' + (N ([math]::Max(2, 760.0 / [math]::Max(1, $CaptureSeconds) - 2)) '0.0') + '" height="' + (N $h '0.0') + '" fill="' + $color + '"><title>Second ' + $second + ': ' + $bins[$second].Count + ' calls; max interval ' + (N $bins[$second].MaxInterval) + ' ms; max call ' + (N $bins[$second].MaxCall) + ' ms</title></rect>')
    }
    $plots.Add('<div class="plot"><h3>' + $label + ' · completed calls per second</h3><svg viewBox="0 0 820 210" role="img" aria-label="' + $label + ' calls per second"><path d="M34 20 V175 H795" fill="none" stroke="#64748b"/><text x="3" y="30" fill="#94a3b8" font-size="12">' + $maxBin + '</text><text x="3" y="181" fill="#94a3b8" font-size="12">0</text>' + ($bars -join '') + '</svg></div>')
}

if ($viewportSet.Count -gt 1) {
    $descriptions = @($viewportSet.Keys | Sort-Object | ForEach-Object { "$_=$($viewportSet[$_] -join ',')" })
    if (@($viewportSet.Values | ForEach-Object { $_ } | Select-Object -Unique).Count -ne 1) {
        $warnings.Add('FAIRNESS: renderer client viewports differ: ' + ($descriptions -join '; ') + '. Do not treat results as an equal-resolution comparison.')
    }
}
if ($coverage.Count -gt 1) {
    $lengths = @($coverage.Values)
    if (($lengths | Measure-Object -Maximum).Maximum - ($lengths | Measure-Object -Minimum).Minimum -gt 1.0) {
        $warnings.Add('FAIRNESS: per-engine frame-record durations differ by more than one second.')
    }
}
if ($totalRecords -eq 0) { $warnings.Add('No usable per-frame data. FPS, frame timing and performance conclusions are unavailable.') }
$warningHtml = if ($warnings.Count) { '<div class="warning"><strong>Capture diagnostics · ' + $warnings.Count + ' warning(s)</strong><ul>' + (($warnings | ForEach-Object { '<li>' + (H $_) + '</li>' }) -join '') + '</ul></div>' } else { '<div class="pass">Capture checks: expected frame files found; frame rows and client dimensions appear consistent.</div>' }
$header = @'
<style>
.metric-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px}.metric,.plot{border:1px solid #334155;background:#111c2c;border-radius:14px;padding:20px}.metric{border-top:4px solid var(--accent)}.big{font-size:44px;font-weight:800;letter-spacing:-.04em;margin:10px 0}.big small{font-size:13px;font-weight:500;color:#94a3b8}.small{color:#94a3b8;font-size:12px}.statgrid{border-top:1px solid #334155;margin-top:14px;padding-top:14px;display:grid;grid-template-columns:1fr auto;gap:10px;font-size:12px}.statgrid span{color:#94a3b8}.warning,.pass{padding:16px;border-radius:10px;margin:18px 0;background:#422022;border:1px solid #dc6565}.pass{background:#113b33;border-color:#34d399}.wide{overflow-x:auto;background:#111c2c;border-radius:12px;border:1px solid #334155}.wide table{min-width:1950px;font-variant-numeric:tabular-nums}.plots{display:grid;grid-template-columns:1fr 1fr;gap:14px}.plot svg{width:100%;height:auto}.provenance{font-size:12px;overflow-wrap:anywhere;color:#94a3b8}@media(max-width:900px){.plots{grid-template-columns:1fr}}
</style>
'@
$columns = @('Engine','Calls','Calls/s','1% low','0.1% low','Interval mean','Interval min','Interval P50','Interval P95','Interval P99','Interval max','Interval SD','Over 16/33/50/100ms','Call mean','Call P50','Call P95','Call P99','Call max','UI mean','UI P95','Cmd mean','Cmd peak','Viewport')
$thead = (($columns | ForEach-Object { '<th>' + (H $_) + '</th>' }) -join '')
$dashboard = '<h2>Frame throughput · primary metrics</h2><p>Completed draw calls per second, not confirmed monitor refresh. GDI is a loop-driven offscreen BitBlt, NOT a WM_PAINT event. Vulkan, OpenGL and D3D9 measure synchronous render/present call returns, not independently verified display times.</p>' + $warningHtml + '<div class="metric-grid">' + ($cards -join '') + '</div>'
if ($rows.Count) {
    $dashboard += '<h2>Frame timing laboratory</h2><p>Milliseconds unless marked. Intervals are completion-to-completion; call is synchronous native renderer wall time; UI is scene lookup and input processing. Nearest-rank percentiles. 1% low = 1000 / P99 interval; 0.1% low = 1000 / P99.9 interval (only shown with at least 1000 samples). Thresholds count late completion intervals, NOT proven dropped frames. Anomalies and long gaps are retained, not silently erased.</p><div class="wide"><table><thead><tr>' + $thead + '</tr></thead><tbody>' + ($rows -join '') + '</tbody></table></div><h2>Throughput timeline</h2><p>Per-second completed call totals. Hover bars for maximum interval and call time in that second. Unlike downsampled traces, each bar includes every captured row.</p><div class="plots">' + ($plots -join '') + '</div><h2>Longest completion gaps</h2><div class="wide"><table><thead><tr><th>Engine</th><th>Frame</th><th>Elapsed s</th><th>Interval ms</th><th>Render ms</th><th>UI ms</th></tr></thead><tbody>' + ($anomalies -join '') + '</tbody></table></div>'
}
$dashboard += '<h2>Capture provenance</h2><p class="provenance">Process source: ' + (H ([IO.Path]::GetFullPath($CsvPath))) + '<br>Frame folder: ' + (H ([IO.Path]::GetFullPath($FrameDirectory))) + '<br>Frame files: ' + (H ($sourceNames -join ', ')) + '<br>Requested selection: ' + (N $WarmupSeconds) + '–' + (N ($WarmupSeconds + $CaptureSeconds)) + ' seconds on each application monotonic clock; clocks start separately. Process sample duration: ' + (N $maxProcessSeconds) + ' seconds. Capture timestamps are not globally synchronized.</p>'
& (Join-Path $PSScriptRoot 'report-queue-desk.ps1') -CsvPath $CsvPath -OutputPath $OutputPath
$html = [IO.File]::ReadAllText([IO.Path]::GetFullPath($OutputPath))
$html = $html.Replace('</head>', $header + '</head>')
$html = $html.Replace('<h2>Process resources</h2>', $dashboard + '<h2>Process resources · supporting data</h2>')
$html = $html.Replace('The application''s FPS and CALL MS counters are only visible in the windows; this CSV does not capture them.', 'Per-frame CSV files supply the throughput and timing metrics above when present; the process CSV alone cannot supply frame timing.')
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), $html, (New-Object Text.UTF8Encoding $false))
Write-Host "Benchmark HTML: $([IO.Path]::GetFullPath($OutputPath)); frame records: $totalRecords; diagnostics: $($warnings.Count)"
