param(
    [string]$GradleLauncher = '',
    [ValidateSet('Debug','Release','RelWithDebInfo','MinSizeRel')]
    [string]$BuildType = 'Release'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root 'native/build'

function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

$cmake = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmake) {
    $candidate = 'C:\Program Files\CMake\bin\cmake.exe'
    if (Test-Path $candidate) { $cmake = Get-Command $candidate }
}
if (-not $cmake) { throw 'CMake is required.' }

if (-not $env:JAVA_HOME) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javac) { throw 'JDK 17+ is required.' }
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javac.Source)
}
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'include/jni.h'))) {
    throw 'JAVA_HOME must point to a full JDK.'
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
if (-not $gcc) { throw 'MinGW-w64 GCC is required.' }

$ninja = Get-Command ninja -ErrorAction SilentlyContinue
$make = Get-Command mingw32-make -ErrorAction SilentlyContinue
if ($ninja) {
    $generator = 'Ninja'
} elseif ($make) {
    $generator = 'MinGW Makefiles'
} else {
    throw 'Ninja or mingw32-make is required.'
}

Run $cmake.Source @(
    '-S', (Join-Path $root 'native'),
    '-B', $build,
    '-G', $generator,
    "-DCMAKE_BUILD_TYPE=$BuildType",
    '-DBUILD_TESTING=OFF',
    '-DKD_REQUIRE_JNI=ON',
    "-DCMAKE_C_COMPILER=$($gcc.Source)"
)

Run $cmake.Source @(
    '--build', $build,
    '--target', 'kanvas_native',
    '--config', $BuildType
)

if ($GradleLauncher) {
    $gradle = $GradleLauncher
} else {
    $wrapper = Join-Path $root 'gradlew.bat'
    if (Test-Path $wrapper) {
        $gradle = $wrapper
    } else {
        $cmd = Get-Command gradle -ErrorAction SilentlyContinue
        if (-not $cmd) { throw 'Gradle is required to build the Kanvas agent.' }
        $gradle = $cmd.Source
    }
}

Run $gradle @('-p', $root, ':integration-agent:jar')
Write-Host 'Kanvas runtime ready.'
