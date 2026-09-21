param(
    [Parameter(Mandatory = $true)][int[]]$ProcessIds,
    [ValidateRange(320, 7680)][int]$ClientWidth = 960,
    [ValidateRange(240, 4320)][int]$ClientHeight = 540
)

$ErrorActionPreference = 'Stop'
if (-not ('QueueDeskWindowGeometry' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class QueueDeskWindowGeometry {
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll", SetLastError=true)] public static extern bool GetClientRect(IntPtr handle, out Rect rect);
    [DllImport("user32.dll", SetLastError=true)] public static extern bool GetWindowRect(IntPtr handle, out Rect rect);
    [DllImport("user32.dll", SetLastError=true)] public static extern bool SetWindowPos(IntPtr handle, IntPtr after, int x, int y, int width, int height, uint flags);
}
'@
}

$results = New-Object 'System.Collections.Generic.List[object]'
foreach ($pidValue in $ProcessIds) {
    $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if (-not $process) { throw "Benchmark process $pidValue has exited." }
    $handle = $process.MainWindowHandle
    if ($handle -eq [IntPtr]::Zero) { throw "Benchmark process $pidValue has no main window." }
    $actualWidth = 0
    $actualHeight = 0
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $client = New-Object QueueDeskWindowGeometry+Rect
        $outer = New-Object QueueDeskWindowGeometry+Rect
        if (-not [QueueDeskWindowGeometry]::GetClientRect($handle, [ref]$client) -or
            -not [QueueDeskWindowGeometry]::GetWindowRect($handle, [ref]$outer)) {
            throw "Could not inspect window for PID $pidValue."
        }
        $actualWidth = $client.Right - $client.Left
        $actualHeight = $client.Bottom - $client.Top
        if ($actualWidth -eq $ClientWidth -and $actualHeight -eq $ClientHeight) { break }
        $outerWidth = $outer.Right - $outer.Left
        $outerHeight = $outer.Bottom - $outer.Top
        $flags = [uint32]0x0016
        if (-not [QueueDeskWindowGeometry]::SetWindowPos($handle, [IntPtr]::Zero, 0, 0,
            $outerWidth + $ClientWidth - $actualWidth,
            $outerHeight + $ClientHeight - $actualHeight, $flags)) {
            throw "SetWindowPos failed for PID $pidValue."
        }
        Start-Sleep -Milliseconds 100
    }
    if ($actualWidth -ne $ClientWidth -or $actualHeight -ne $ClientHeight) {
        throw "PID $pidValue client size is ${actualWidth}x${actualHeight}, expected ${ClientWidth}x${ClientHeight}."
    }
    $results.Add([pscustomobject]@{ PID = $pidValue; ClientWidth = $actualWidth; ClientHeight = $actualHeight })
}
$results | Format-Table -AutoSize
Write-Host "Client area verified for $($results.Count) benchmark windows: ${ClientWidth}x${ClientHeight}."
