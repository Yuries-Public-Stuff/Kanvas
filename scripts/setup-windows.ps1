param(
    [switch]$InstallTools,
    [switch]$SkipGradleCheck
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Add-PathIfPresent([string]$Path) {
    if ($Path -and (Test-Path $Path)) {
        $parts = $env:PATH -split ';'
        if ($parts -notcontains $Path) {
            $env:PATH = "$Path;$env:PATH"
        }
    }
}

function Refresh-KnownPaths {
    if ($env:LOCALAPPDATA) {
        Add-PathIfPresent (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links')
    }
    Add-PathIfPresent 'C:\msys64\ucrt64\bin'
    Add-PathIfPresent 'C:\msys64\mingw64\bin'
    Add-PathIfPresent 'C:\Program Files\CMake\bin'
    Add-PathIfPresent 'C:\Program Files\Ninja'

    $jdkRoots = @(
        'C:\Program Files\Microsoft\jdk-17*',
        'C:\Program Files\Eclipse Adoptium\jdk-17*',
        'C:\Program Files\Java\jdk-17*'
    )
    $jdk = Get-ChildItem $jdkRoots -Directory -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($jdk) {
        Add-PathIfPresent (Join-Path $jdk.FullName 'bin')
        if (-not $env:JAVA_HOME -and (Test-Path (Join-Path $jdk.FullName 'include\jni.h'))) {
            $env:JAVA_HOME = $jdk.FullName
        }
    }
}

function Have([string]$Name) {
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Install-WingetPackage([string]$Id) {
    & winget install --id $Id -e --source winget --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) {
        throw "WinGet installation failed for $Id (exit $LASTEXITCODE)"
    }
}

if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'Kotlin Display Windows takeover currently requires 64-bit Windows.'
}

if ($InstallTools) {
    if (-not (Have 'winget')) {
        throw 'WinGet is required for -InstallTools. Install/update App Installer first.'
    }

    if (-not (Have 'cmake')) {
        Write-Host 'Installing CMake...'
        Install-WingetPackage 'Kitware.CMake'
    }
    if (-not (Have 'ninja')) {
        Write-Host 'Installing Ninja...'
        Install-WingetPackage 'Ninja-build.Ninja'
    }
    if (-not (Have 'javac')) {
        Write-Host 'Installing Microsoft OpenJDK 17...'
        Install-WingetPackage 'Microsoft.OpenJDK.17'
    }

    if (-not (Test-Path 'C:\msys64\usr\bin\bash.exe')) {
        Write-Host 'Installing MSYS2...'
        Install-WingetPackage 'MSYS2.MSYS2'
    }

    Refresh-KnownPaths

    $msysBash = 'C:\msys64\usr\bin\bash.exe'
    $ucrtGcc = 'C:\msys64\ucrt64\bin\gcc.exe'
    $gccReady = $false
    if (Test-Path $ucrtGcc) {
        $gccTarget = (& $ucrtGcc -dumpmachine).Trim()
        $gccReady = $LASTEXITCODE -eq 0 -and $gccTarget -match '(mingw|windows)'
    }

    if (-not $gccReady -and (Test-Path $msysBash)) {
        Write-Host 'Updating MSYS2 before installing UCRT64 MinGW-w64 GCC...'
        & $msysBash -lc 'pacman -Syu --noconfirm'
        if ($LASTEXITCODE -ne 0) {
            throw "MSYS2 full update failed (exit $LASTEXITCODE)"
        }

        # Restart MSYS2 after the core update.
        Write-Host 'Installing UCRT64 MinGW-w64 GCC...'
        & $msysBash -lc 'pacman -S --noconfirm --needed mingw-w64-ucrt-x86_64-gcc'
        if ($LASTEXITCODE -ne 0) {
            throw "MSYS2 GCC installation failed (exit $LASTEXITCODE)"
        }
    } elseif ($gccReady) {
        Write-Host "UCRT64 MinGW-w64 GCC already works ($gccTarget); skipping package changes."
    }
}

Refresh-KnownPaths

$checks = [ordered]@{}
$checks['64-bit Windows'] = [Environment]::Is64BitOperatingSystem
$checks['CMake'] = Have 'cmake'
$checks['Ninja or mingw32-make'] = (Have 'ninja') -or (Have 'mingw32-make')
$checks['JDK javac'] = Have 'javac'

$gcc = Get-Command gcc -ErrorAction SilentlyContinue
$gccOk = $false
$gccTarget = 'missing'
if ($gcc) {
    $gccTarget = (& $gcc.Source -dumpmachine).Trim()
    $gccOk = $LASTEXITCODE -eq 0 -and $gccTarget -match '(mingw|windows)'
}
$checks['MinGW-w64 GCC'] = $gccOk

if ($env:JAVA_HOME) {
    $checks['JAVA_HOME JNI headers'] = Test-Path (Join-Path $env:JAVA_HOME 'include\jni.h')
} elseif (Have 'javac') {
    $javac = Get-Command javac
    $javaBin = Split-Path -Parent $javac.Source
    $candidate = Split-Path -Parent $javaBin
    if (Test-Path (Join-Path $candidate 'include\jni.h')) {
        $env:JAVA_HOME = $candidate
        $checks['JAVA_HOME JNI headers'] = $true
    } else {
        $checks['JAVA_HOME JNI headers'] = $false
    }
} else {
    $checks['JAVA_HOME JNI headers'] = $false
}

if (-not $SkipGradleCheck) {
    $checks['Gradle wrapper/system Gradle'] =
        (Test-Path (Join-Path $root 'gradlew.bat')) -or (Have 'gradle')
}

Write-Host ''
Write-Host 'Kotlin Display Windows setup doctor'
Write-Host '----------------------------------'
$failed = @()
foreach ($entry in $checks.GetEnumerator()) {
    $status = if ($entry.Value) { 'OK' } else { 'MISSING' }
    Write-Host ('{0,-30} {1}' -f $entry.Key, $status)
    if (-not $entry.Value) { $failed += $entry.Key }
}
Write-Host ('{0,-30} {1}' -f 'GCC target', $gccTarget)
if ($env:JAVA_HOME) {
    Write-Host ('{0,-30} {1}' -f 'JAVA_HOME', $env:JAVA_HOME)
}

if ($failed.Count -gt 0) {
    Write-Host ''
    Write-Host 'Missing prerequisites:'
    $failed | ForEach-Object { Write-Host "  - $_" }
    if (-not $InstallTools) {
        Write-Host ''
        Write-Host 'Run this to install the standard toolchain:'
        Write-Host '  .\scripts\setup-windows.ps1 -InstallTools'
    }
    exit 1
}

Write-Host ''
Write-Host 'Windows build prerequisites are ready.'
Write-Host 'Next: .\scripts\build-windows.ps1 -Clean'
