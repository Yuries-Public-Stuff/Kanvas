param(
    [Parameter(Mandatory = $true)][int]$NativePid,
    [Parameter(Mandatory = $true)][int]$VulkanPid,
    [int]$OpenGlPid = 0,
    [int]$Direct3d9Pid = 0,
    [ValidateRange(2, 600)][int]$Seconds = 15,
    [ValidateRange(0, 600)][double]$FrameWarmupSeconds = 0,
    [string]$OutputPath = (Join-Path $env:TEMP ("queue-desk-{0}.csv" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))),
    [string]$FrameDirectory = '',
    [switch]$HtmlReport
)

$ErrorActionPreference = 'Stop'
$targets = @(
    @{ Name = 'gdi'; Id = $NativePid },
    @{ Name = 'vulkan'; Id = $VulkanPid }
)
if ($OpenGlPid -gt 0) { $targets += @{ Name = 'opengl'; Id = $OpenGlPid } }
if ($Direct3d9Pid -gt 0) { $targets += @{ Name = 'direct3d9'; Id = $Direct3d9Pid } }
$ids = @($targets | ForEach-Object { $_.Id })
if (@($ids | Where-Object { $_ -le 0 }).Count -gt 0 -or @($ids | Select-Object -Unique).Count -ne $ids.Count) {
    throw 'Supply distinct positive process IDs for each backend.'
}

$logicalProcessors = [Environment]::ProcessorCount
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$previous = @{}
$rows = New-Object 'System.Collections.Generic.List[object]'

function Take-Sample([double]$elapsed) {
    foreach ($target in $targets) {
        $process = Get-Process -Id $target.Id -ErrorAction SilentlyContinue
        if (-not $process) {
            $rows.Add([pscustomobject]@{
                Engine = $target.Name; ElapsedSeconds = [math]::Round($elapsed, 3)
                Pid = $target.Id; Alive = $false; CpuPercentOfMachine = $null
                WorkingSetMB = $null; PrivateMemoryMB = $null; Handles = $null
            })
            continue
        }
        try {
            $cpuSeconds = $process.TotalProcessorTime.TotalSeconds
            $cpuPercent = $null
            if ($previous.ContainsKey($target.Name)) {
                $prior = $previous[$target.Name]
                $deltaTime = $elapsed - $prior.Time
                $deltaCpu = $cpuSeconds - $prior.Cpu
                if ($deltaTime -gt 0 -and $deltaCpu -ge 0) {
                    $cpuPercent = [math]::Round(100.0 * $deltaCpu / ($deltaTime * $logicalProcessors), 2)
                }
            }
            $previous[$target.Name] = @{ Time = $elapsed; Cpu = $cpuSeconds }
            $rows.Add([pscustomobject]@{
                Engine = $target.Name; ElapsedSeconds = [math]::Round($elapsed, 3)
                Pid = $target.Id; Alive = $true; CpuPercentOfMachine = $cpuPercent
                WorkingSetMB = [math]::Round($process.WorkingSet64 / 1MB, 2)
                PrivateMemoryMB = [math]::Round($process.PrivateMemorySize64 / 1MB, 2)
                Handles = $process.HandleCount
            })
        } catch {
            $rows.Add([pscustomobject]@{
                Engine = $target.Name; ElapsedSeconds = [math]::Round($elapsed, 3)
                Pid = $target.Id; Alive = $false; CpuPercentOfMachine = $null
                WorkingSetMB = $null; PrivateMemoryMB = $null; Handles = $null
            })
        }
    }
}

Take-Sample 0.0
for ($second = 1; $second -le $Seconds; $second++) {
    $delay = [math]::Max(0, $second * 1000 - $watch.ElapsedMilliseconds)
    if ($delay -gt 0) { Start-Sleep -Milliseconds $delay }
    Take-Sample $watch.Elapsed.TotalSeconds
}

$parent = Split-Path -Parent $OutputPath
if ($parent -and -not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}
$rows | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Encoding UTF8
Write-Host "Saved $($rows.Count) process samples to $OutputPath"
if ($HtmlReport) {
    if (-not $FrameDirectory) { $FrameDirectory = $parent }
    $htmlPath = [IO.Path]::ChangeExtension($OutputPath, '.html')
    & (Join-Path $PSScriptRoot 'report-queue-desk-frames.ps1') -CsvPath $OutputPath -FrameDirectory $FrameDirectory -OutputPath $htmlPath -WarmupSeconds $FrameWarmupSeconds -CaptureSeconds $Seconds
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "HTML report generator failed: $LASTEXITCODE" }
}
