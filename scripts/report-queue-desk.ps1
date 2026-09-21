param(
    [Parameter(Mandatory = $true)][string]$CsvPath,
    [string]$OutputPath = [IO.Path]::ChangeExtension($CsvPath, '.html')
)

$ErrorActionPreference = 'Stop'
$data = @(Import-Csv -LiteralPath $CsvPath)
if ($data.Count -eq 0) { throw 'No sample rows found.' }
$groups = @($data | Group-Object Engine)
$colors = @{ gdi = '#60a5fa'; vulkan = '#34d399'; opengl = '#fbbf24'; direct3d9 = '#c084fc' }
$culture = [Globalization.CultureInfo]::InvariantCulture
function Number([double]$value, [string]$format = '0.##') {
    return $value.ToString($format, $culture)
}
function Escape([string]$value) {
    return [Net.WebUtility]::HtmlEncode($value)
}
$maxTime = 1.0
$maxMemory = 1.0
$maxCpu = 1.0
foreach ($sample in $data) {
    $maxTime = [math]::Max($maxTime, [double]$sample.ElapsedSeconds)
    if ($sample.WorkingSetMB -ne '') { $maxMemory = [math]::Max($maxMemory, [double]$sample.WorkingSetMB) }
    if ($sample.CpuPercentOfMachine -ne '') { $maxCpu = [math]::Max($maxCpu, [double]$sample.CpuPercentOfMachine) }
}

function Plot([string]$property, [double]$ceiling, [string]$label) {
    $lines = New-Object 'System.Collections.Generic.List[string]'
    foreach ($group in $groups) {
        $points = New-Object 'System.Collections.Generic.List[string]'
        foreach ($sample in $group.Group) {
            if ($sample.$property -eq '' -or $sample.Alive -ne 'True') { continue }
            $x = 52.0 + 800.0 * ([double]$sample.ElapsedSeconds / $maxTime)
            $y = 226.0 - 188.0 * ([double]$sample.$property / $ceiling)
            $points.Add((Number $x '0.0') + ',' + (Number $y '0.0'))
        }
        $color = if ($colors.ContainsKey($group.Name)) { $colors[$group.Name] } else { '#94a3b8' }
        if ($points.Count -gt 0) {
            $lines.Add('<polyline fill="none" stroke="' + $color + '" stroke-width="2.5" points="' + ($points -join ' ') + '"/>')
        }
    }
    return '<div class="chart"><h3>' + (Escape $label) + '</h3><svg viewBox="0 0 880 260" role="img" aria-label="' + (Escape $label) + ' over sample time"><path d="M52 30 V226 H852" fill="none" stroke="#64748b" stroke-width="1"/><path d="M52 128 H852" stroke="#334155" stroke-dasharray="4 5"/><text x="6" y="43" fill="#94a3b8" font-size="12">' + (Number $ceiling) + '</text><text x="14" y="229" fill="#94a3b8" font-size="12">0</text><text x="810" y="248" fill="#94a3b8" font-size="12">' + (Number $maxTime) + ' s</text>' + ($lines -join '') + '</svg></div>'
}

$summary = New-Object 'System.Collections.Generic.List[string]'
$legend = New-Object 'System.Collections.Generic.List[string]'
foreach ($group in $groups) {
    $valid = @($group.Group | Where-Object { $_.Alive -eq 'True' -and $_.WorkingSetMB -ne '' })
    $cpu = @($valid | Where-Object { $_.CpuPercentOfMachine -ne '' })
    $meanCpu = if ($cpu.Count -gt 0) { Number (($cpu | Measure-Object -Property CpuPercentOfMachine -Average).Average) } else { 'n/a' }
    $peakRam = if ($valid.Count -gt 0) { Number (($valid | Measure-Object -Property WorkingSetMB -Maximum).Maximum) } else { 'n/a' }
    $peakPrivate = if ($valid.Count -gt 0) { Number (($valid | Measure-Object -Property PrivateMemoryMB -Maximum).Maximum) } else { 'n/a' }
    $peakHandles = if ($valid.Count -gt 0) { ($valid | Measure-Object -Property Handles -Maximum).Maximum } else { 'n/a' }
    $pid = Escape ([string]$group.Group[0].Pid)
    $name = Escape ([string]$group.Name.ToUpperInvariant())
    $color = if ($colors.ContainsKey($group.Name)) { $colors[$group.Name] } else { '#94a3b8' }
    $alive = @($group.Group | Where-Object { $_.Alive -eq 'False' }).Count -eq 0
    $status = if ($alive) { 'Observed alive' } else { 'Exit observed' }
    $summary.Add('<tr><td><span class="dot" style="background:' + $color + '"></span>' + $name + '</td><td>' + $pid + '</td><td>' + $meanCpu + '</td><td>' + $peakRam + '</td><td>' + $peakPrivate + '</td><td>' + $peakHandles + '</td><td>' + $status + '</td></tr>')
    $legend.Add('<span><i style="background:' + $color + '"></i>' + $name + '</span>')
}

$details = New-Object 'System.Collections.Generic.List[string]'
foreach ($sample in $data) {
    $cells = @($sample.Engine, $sample.ElapsedSeconds, $sample.Pid, $sample.Alive,
               $sample.CpuPercentOfMachine, $sample.WorkingSetMB, $sample.PrivateMemoryMB, $sample.Handles)
    $details.Add('<tr>' + (($cells | ForEach-Object { '<td>' + (Escape ([string]$_)) + '</td>' }) -join '') + '</tr>')
}
$timestamp = Escape (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
$source = Escape ([IO.Path]::GetFullPath($CsvPath))
$memoryChart = Plot 'WorkingSetMB' $maxMemory 'Working set (MB)'
$cpuChart = Plot 'CpuPercentOfMachine' $maxCpu 'CPU share of machine (%)'
$html = @"
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Queue Desk | Renderer comparison</title>
<style>
:root{color-scheme:dark;font-family:Segoe UI,system-ui,sans-serif;background:#0b1220;color:#e2e8f0}
*{box-sizing:border-box}body{margin:0;padding:32px;max-width:1300px;margin-inline:auto}h1{font-size:32px;margin:0 0 8px}h2{margin:30px 0 12px}h3{font-size:15px;margin:0 0 12px}p{color:#94a3b8;line-height:1.65}header{padding:30px;border:1px solid #334155;border-radius:16px;background:linear-gradient(120deg,#1e293b,#0f172a)}.eyebrow{text-transform:uppercase;letter-spacing:.15em;color:#7dd3fc;font-size:12px;font-weight:700}.meta{color:#cbd5e1;font-size:13px}.pill{display:inline-block;padding:6px 10px;background:#243244;color:#93c5fd;border-radius:24px;margin:8px 6px 0 0;font-size:12px}.panel{border:1px solid #334155;border-radius:12px;padding:18px;background:#111c2c;overflow-x:auto}table{width:100%;border-collapse:collapse;font-size:13px;text-align:left}th,td{padding:12px 9px;border-bottom:1px solid #334155}th{color:#94a3b8;font-weight:600;white-space:nowrap}tbody tr:hover{background:#1e293b}.dot,.legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:9px}.legend{display:flex;gap:20px;flex-wrap:wrap;margin:20px 0;color:#cbd5e1;font-size:12px}.charts{display:grid;grid-template-columns:1fr 1fr;gap:18px}.chart{padding:17px;background:#111c2c;border:1px solid #334155;border-radius:12px}.chart svg{width:100%;height:auto}.note{border-left:3px solid #38bdf8;padding:4px 18px;background:#13263b;border-radius:0 8px 8px 0}details{margin-top:24px}summary{cursor:pointer;padding:16px;border:1px solid #334155;border-radius:10px}footer{margin-top:32px;font-size:12px;color:#64748b}@media(max-width:900px){.charts{grid-template-columns:1fr}body{padding:14px}}
</style></head><body>
<header><div class="eyebrow">Rendering comparison / local run</div><h1>Queue Desk</h1><p>Same Kotlin/Native UI scene and ordered rectangle commands; independent C rendering backends.</p><div class="meta">Generated $timestamp &nbsp; · &nbsp; $($data.Count) samples &nbsp; · &nbsp; $($groups.Count) engines</div><div><span class="pill">GDI</span><span class="pill">Vulkan</span><span class="pill">OpenGL</span><span class="pill">Direct3D 9</span></div></header>
<h2>Process resources</h2><div class="panel"><table><thead><tr><th>Renderer</th><th>PID</th><th>Mean CPU %</th><th>Peak working MB</th><th>Peak private MB</th><th>Peak handles</th><th>State</th></tr></thead><tbody>$($summary -join "`n")</tbody></table></div>
<div class="legend">$($legend -join '')</div><div class="charts">$memoryChart $cpuChart</div>
<h2>What this measures</h2><div class="note"><p>CPU usage is normalized across logical processors. Memory and handle counts come from Windows process samples. These are not GPU time, end-to-end latency, power draw, or display refresh measurements. The application's FPS and CALL MS counters are only visible in the windows; this CSV does not capture them. Matching layout does not imply identical API synchronization or swap intervals.</p><p>Missing values mean the process was unavailable or a CPU delta was not yet possible. Backend availability and successful presentation must be established by a real Windows run.</p></div>
<details><summary>Raw sample rows ($($data.Count))</summary><div class="panel"><table><thead><tr><th>Engine</th><th>Seconds</th><th>PID</th><th>Alive</th><th>CPU %</th><th>Working MB</th><th>Private MB</th><th>Handles</th></tr></thead><tbody>$($details -join "`n")</tbody></table></div></details>
<footer>Source CSV: $source · Generated locally; no CDN, network upload, or analytics.</footer></body></html>
"@
$folder = Split-Path -Parent $OutputPath
if ($folder -and -not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Path $folder -Force | Out-Null }
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), $html, (New-Object Text.UTF8Encoding $false))
Write-Host "HTML report: $([IO.Path]::GetFullPath($OutputPath))"
