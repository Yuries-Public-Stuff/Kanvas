param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root 'native/build-release-check'

function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

$gradle = Join-Path $root 'gradlew.bat'
if (-not (Test-Path $gradle)) {
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if (-not $cmd) { throw 'Gradle is required.' }
    $gradle = $cmd.Source
}

$cmake = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmake) {
    $candidate = 'C:\Program Files\CMake\bin\cmake.exe'
    if (Test-Path $candidate) {
        $cmake = Get-Command $candidate
    }
}
if (-not $cmake) { throw 'CMake is required.' }

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
if (-not $gcc) { throw 'MinGW-w64 GCC is required.' }

$ninja = Get-Command ninja -ErrorAction SilentlyContinue
$make = Get-Command mingw32-make -ErrorAction SilentlyContinue
$generator = if ($ninja) {
    'Ninja'
} elseif ($make) {
    'MinGW Makefiles'
} else {
    throw 'Ninja or mingw32-make is required.'
}

Write-Host '==> Plugin tests'
Run $gradle @('-p', (Join-Path $root 'gradle-plugin'), 'test', '--stacktrace')

Write-Host '==> Native build'
Run $cmake.Source @(
    '-S', (Join-Path $root 'native'),
    '-B', $build,
    '-G', $generator,
    '-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_TESTING=ON',
    '-DKD_REQUIRE_JNI=ON',
    "-DCMAKE_C_COMPILER=$($gcc.Source)"
)
Run $cmake.Source @('--build', $build, '--config', 'Release')

Write-Host '==> Native tests'
Run 'ctest' @(
    '--test-dir', $build,
    '-C', 'Release',
    '--output-on-failure'
)

Write-Host '==> Agent'
Run $gradle @(
    '-p', $root,
    ':integration-agent:test',
    ':integration-agent:jar',
    '--stacktrace'
)

Write-Host 'Kanvas release check passed.'
