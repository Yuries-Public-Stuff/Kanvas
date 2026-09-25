param(
    [switch]$Clean,
    [switch]$SkipKotlin,
    [switch]$SkipTests,
    [switch]$ProbeSurface,
    [switch]$FirstFrame,
    [switch]$KotlinDemo,
    [switch]$NativeDemo,
    [switch]$OpenGlDemo,
    [Alias('Direct3d9Demo')][switch]$DirectXDemo,
    [switch]$CompareDemos,
    [switch]$CompareAll,
    [switch]$LegacyControls,
    [switch]$HtmlReport,
    [ValidateRange(2, 600)][int]$ReportSeconds = 15,
    [ValidateRange(320, 7680)][int]$ClientWidth = 960,
    [ValidateRange(240, 4320)][int]$ClientHeight = 540,
    [string]$GradleLauncher = '',
    [switch]$WindowTestsOnly
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if ($env:LOCALAPPDATA) {
    $wingetLinks = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links'
    if (Test-Path $wingetLinks) {
        $env:PATH = "$wingetLinks;$env:PATH"
    }
}
function Run([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "$program failed with exit code $LASTEXITCODE" }
}
if (-not [Environment]::Is64BitOperatingSystem) { throw 'Windows x64 is required.' }
if (($KotlinDemo -or $NativeDemo -or $OpenGlDemo -or $DirectXDemo -or $CompareDemos -or $CompareAll) -and $SkipKotlin) {
    throw 'Matched renderers need Kotlin. Use -LegacyControls -SkipKotlin for the old Win32 sample.'
}
$launchCount = @($ProbeSurface, $FirstFrame, $KotlinDemo, $NativeDemo, $OpenGlDemo, $DirectXDemo, $CompareDemos, $CompareAll, $LegacyControls) | Where-Object { $_ } | Measure-Object | Select-Object -ExpandProperty Count
if ($launchCount -gt 1) { throw 'Select only one demo, comparison, or probe.' }
if ($HtmlReport -and -not ($CompareDemos -or $CompareAll)) { throw '-HtmlReport requires -CompareDemos or -CompareAll.' }
$cmake = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmake) {
    $cmakeCandidate = 'C:\Program Files\CMake\bin\cmake.exe'
    if (Test-Path $cmakeCandidate) {
        $cmake = Get-Command $cmakeCandidate
        $env:PATH = "$(Split-Path -Parent $cmakeCandidate);$env:PATH"
    } else {
        throw 'CMake is required. Run .\scripts\setup-windows.ps1 -InstallTools.'
    }
}

if (-not $SkipKotlin) {
    if (-not $env:JAVA_HOME) {
        $javac = Get-Command javac -ErrorAction SilentlyContinue
        if (-not $javac) {
            $jdkRoots = @(
                'C:\Program Files\Microsoft\jdk-17*',
                'C:\Program Files\Eclipse Adoptium\jdk-17*',
                'C:\Program Files\Java\jdk-17*'
            )
            $jdk = Get-ChildItem $jdkRoots -Directory -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending |
                Select-Object -First 1
            if ($jdk -and (Test-Path (Join-Path $jdk.FullName 'bin\javac.exe'))) {
                $env:JAVA_HOME = $jdk.FullName
                $env:PATH = "$(Join-Path $jdk.FullName 'bin');$env:PATH"
                $javac = Get-Command (Join-Path $jdk.FullName 'bin\javac.exe')
            }
        }
        if (-not $javac) {
            throw 'JDK 17+ is required. Run .\scripts\setup-windows.ps1 -InstallTools.'
        }
        if (-not $env:JAVA_HOME) {
            $javaBin = Split-Path -Parent $javac.Source
            $env:JAVA_HOME = Split-Path -Parent $javaBin
        }
    }
    $jniHeader = Join-Path $env:JAVA_HOME 'include/jni.h'
    if (-not (Test-Path $jniHeader)) {
        throw "JAVA_HOME does not point to a full JDK with JNI headers: $env:JAVA_HOME"
    }
}

$gcc = Get-Command gcc -ErrorAction SilentlyContinue
if (-not $gcc) {
    foreach ($candidate in @(
        'C:\msys64\ucrt64\bin\gcc.exe',
        'C:\msys64\mingw64\bin\gcc.exe'
    )) {
        if (Test-Path $candidate) {
            $gcc = Get-Command $candidate
            $env:PATH = "$(Split-Path -Parent $candidate);$env:PATH"
            break
        }
    }
}
if (-not $gcc) {
    throw 'MinGW-w64 GCC is required. Run .\scripts\setup-windows.ps1 -InstallTools.'
}
$target = (& $gcc.Source -dumpmachine).Trim()
if ($LASTEXITCODE -ne 0 -or $target -notmatch '(mingw|windows)') { throw "Use a MinGW-w64 GCC toolchain. Found: $target" }
$ninja = Get-Command ninja -ErrorAction SilentlyContinue
if (-not $ninja) {
    foreach ($candidate in @(
        'C:\Program Files\Ninja\ninja.exe',
        'C:\Program Files\CMake\bin\ninja.exe',
        'C:\msys64\ucrt64\bin\ninja.exe'
    )) {
        if (Test-Path $candidate) {
            $ninja = Get-Command $candidate
            $env:PATH = "$(Split-Path -Parent $candidate);$env:PATH"
            break
        }
    }
}
$make = Get-Command mingw32-make -ErrorAction SilentlyContinue
if ($ninja) { $generator = 'Ninja' }
elseif ($make) { $generator = 'MinGW Makefiles' }
else { throw 'Install Ninja or mingw32-make.' }
if ($WindowTestsOnly -and ($ProbeSurface -or $FirstFrame -or $KotlinDemo -or $NativeDemo -or $OpenGlDemo -or $DirectXDemo -or $CompareDemos -or $CompareAll -or $LegacyControls -or $SkipKotlin -or $SkipTests -or $HtmlReport)) {
    throw '-WindowTestsOnly runs only the SDK-free window tests.'
}
$build = Join-Path $root $(if ($WindowTestsOnly) { 'native/window-smoke-build' } else { 'native/build' })
if ($Clean -and (Test-Path $build)) { Remove-Item $build -Recurse -Force }
$source = if ($WindowTestsOnly) { 'native/window-smoke' } else { 'native' }
$configureArgs = @('-S', $source, '-B', $build, '-G', $generator, '-DCMAKE_BUILD_TYPE=Debug', "-DCMAKE_C_COMPILER=$($gcc.Source)")
if (-not $WindowTestsOnly -and -not $SkipKotlin) { $configureArgs += '-DKD_REQUIRE_JNI=ON' }
$needsVulkan = $ProbeSurface -or $KotlinDemo -or $CompareDemos -or $CompareAll
if ($needsVulkan) { $configureArgs += '-DKD_REQUIRE_VULKAN=ON' }
Run $cmake.Source $configureArgs
Run $cmake.Source @('--build', $build, '--config', 'Debug')
if (-not $SkipTests) { Run 'ctest' @('--test-dir', $build, '-C', 'Debug', '--output-on-failure') }
if ($WindowTestsOnly) { Write-Host 'Windows window regressions passed.'; exit 0 }
function BuiltNative([string]$name) {
    $program = Join-Path $build $name
    if (-not (Test-Path $program)) { $program = Join-Path (Join-Path $build 'Debug') $name }
    if (-not (Test-Path $program)) { throw "$name was not built." }
    return $program
}
if ($ProbeSurface -or $FirstFrame -or $LegacyControls) {
    $name = if ($FirstFrame) { 'first_frame.exe' } elseif ($LegacyControls) { 'patch_bay_win32.exe' } else { 'surface_info.exe' }
    Run (BuiltNative $name) @()
}
if ($SkipKotlin) { Write-Host 'Native build finished. Kotlin build skipped.'; exit 0 }
if ($GradleLauncher) {
    if (-not (Test-Path $GradleLauncher)) {
        throw "Gradle launcher not found: $GradleLauncher"
    }
    $gradle = [IO.Path]::GetFullPath($GradleLauncher)
} else {
    $wrapper = Join-Path $root 'gradlew.bat'
    if (Test-Path $wrapper) {
        $gradle = $wrapper
    } else {
        $gradleCommand = Get-Command gradle -ErrorAction SilentlyContinue
        if (-not $gradleCommand) {
            throw 'No Gradle launcher is available. Pass -GradleLauncher C:\path\to\gradlew.bat.'
        }
        $gradle = $gradleCommand.Source
    }
}
$gradleTasks = @(
    ':renderer:jvmTest',
    ':compose-bridge:test',
    ':compose-gpu-demo:classes',
    ':integration-agent:test',
    ':integration-agent:jar'
)

$needsKotlinNativeDemo =
    $KotlinDemo -or
    $NativeDemo -or
    $OpenGlDemo -or
    $DirectXDemo -or
    $CompareDemos -or
    $CompareAll

if ($needsKotlinNativeDemo) {
    $gradleTasks += ':renderer:linkDebugExecutableMingwX64'
}

Run $gradle $gradleTasks
Write-Host 'Windows native/JNI and JVM takeover builds finished.'
if ($needsKotlinNativeDemo) {
    $binaryDir = Join-Path $root 'renderer/build/bin/mingwX64/debugExecutable'
    $program = Get-ChildItem -Path $binaryDir -Filter '*.exe' -File | Select-Object -First 1
    if (-not $program) { throw 'Kotlin executable was not found.' }
    $env:PATH = "$build;$(Join-Path $build 'Debug');$env:PATH"
    if ($CompareAll -or ($CompareDemos -and $HtmlReport)) {
        if (-not $SkipTests) { & (Join-Path $PSScriptRoot 'test-queue-desk-report.ps1') }
        $reportDir = Join-Path $env:TEMP ("queue-desk-{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), [guid]::NewGuid().ToString('N').Substring(0, 6))
        New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
        $processes = @{}
        $modes = if ($CompareAll) { @('gdi', 'vulkan', 'opengl', 'd3d9') } else { @('gdi', 'vulkan') }
        $previousLog = $env:KD_FRAME_LOG_PATH
        $previousLimit = $env:KD_FRAME_LOG_MAX_SECONDS
        try {
            $env:KD_FRAME_LOG_MAX_SECONDS = [string]($ReportSeconds + 5)
            foreach ($mode in $modes) {
                $env:KD_FRAME_LOG_PATH = Join-Path $reportDir "$mode-frames.csv"
                $processes[$mode] = Start-Process -FilePath $program.FullName -ArgumentList "--$mode" -WorkingDirectory $binaryDir -PassThru
                Write-Host "$mode PID: $($processes[$mode].Id)"
            }
        } finally {
            if ($null -eq $previousLog) { Remove-Item Env:KD_FRAME_LOG_PATH -ErrorAction SilentlyContinue }
            else { $env:KD_FRAME_LOG_PATH = $previousLog }
            if ($null -eq $previousLimit) { Remove-Item Env:KD_FRAME_LOG_MAX_SECONDS -ErrorAction SilentlyContinue }
            else { $env:KD_FRAME_LOG_MAX_SECONDS = $previousLimit }
        }
        Start-Sleep -Seconds 2
        foreach ($mode in $modes) {
            $processes[$mode].Refresh()
            if ($processes[$mode].HasExited) { throw "$mode exited before benchmarking (code $($processes[$mode].ExitCode)); frame files remain in $reportDir" }
        }
        & (Join-Path $PSScriptRoot 'normalize-queue-desk-windows.ps1') -ProcessIds @($modes | ForEach-Object { $processes[$_].Id }) -ClientWidth $ClientWidth -ClientHeight $ClientHeight
        if (-not $?) { throw 'Client-area normalization failed; benchmark aborted.' }
        Start-Sleep -Seconds 1
        $options = @{
            NativePid = $processes['gdi'].Id
            VulkanPid = $processes['vulkan'].Id
            Seconds = $ReportSeconds
            FrameWarmupSeconds = 3
            OutputPath = (Join-Path $reportDir 'process.csv')
            FrameDirectory = $reportDir
            HtmlReport = $true
        }
        if ($CompareAll) {
            $options['OpenGlPid'] = $processes['opengl'].Id
            $options['Direct3d9Pid'] = $processes['d3d9'].Id
        }
        & (Join-Path $PSScriptRoot 'sample-patch-bay.ps1') @options
        if (-not $?) { throw 'Sampling or report generation failed.' }
        Write-Host "Comparison data directory: $reportDir"
        Write-Host "HTML report: $(Join-Path $reportDir 'process.html')"
        Write-Host 'Windows remain open for visual tests. Frame logging stops after the configured capture window.'
    } elseif ($CompareDemos) {
        $gdiProcess = Start-Process -FilePath $program.FullName -ArgumentList '--gdi' -WorkingDirectory $binaryDir -PassThru
        Write-Host "GDI process PID: $($gdiProcess.Id)"
        try { Run $program.FullName @('--vulkan') }
        finally { Write-Host "GDI PID: $($gdiProcess.Id). Close its window separately." }
    } else {
        $mode = if ($NativeDemo) { '--gdi' } elseif ($OpenGlDemo) { '--opengl' } elseif ($DirectXDemo) { '--d3d9' } else { '--vulkan' }
        Run $program.FullName @($mode)
    }
} elseif (-not $LegacyControls -and -not $FirstFrame -and -not $ProbeSurface) {
    Write-Host 'Use -NativeDemo (GDI), -KotlinDemo (Vulkan), -OpenGlDemo, -DirectXDemo (D3D9), or -CompareAll (frame-first HTML report).'
}
