param(
    [string]$WakeDir = '',
    [ValidateSet('auto','vulkan','d3d9','opengl','gdi')][string]$Backend = 'auto',
    [switch]$LaunchStrict,
    [switch]$SkipClean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $WakeDir) {
    $WakeDir = Join-Path (Split-Path -Parent $root) 'wake'
}
$WakeDir = [IO.Path]::GetFullPath($WakeDir)

$reportDir = Join-Path $root 'build/parity'
$report = Join-Path $reportDir 'wake-parity-windows.md'
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null

if (-not (Test-Path (Join-Path $WakeDir '.git'))) {
    throw "Wake checkout not found: $WakeDir"
}

function Run-Step {
    param(
        [string]$Name,
        [scriptblock]$Body
    )
    Write-Host ''
    Write-Host "==> $Name"
    $global:LASTEXITCODE = 0
    try {
        & $Body
    } catch {
        throw "$Name failed: $($_.Exception.Message)"
    }
    if (-not $?) {
        throw "$Name failed."
    }
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

$git = Get-Command git -ErrorAction Stop
$wakeSha = (& $git.Source -C $WakeDir rev-parse --short HEAD).Trim()
$wakeBranch = (& $git.Source -C $WakeDir branch --show-current).Trim()
$kdSha = (& $git.Source -C $root rev-parse --short HEAD).Trim()


$wakeGradle = Join-Path $WakeDir 'gradlew.bat'
if (-not (Test-Path $wakeGradle)) {
    $gradle = Get-Command gradle -ErrorAction SilentlyContinue
    if (-not $gradle) { throw 'Wake has no gradlew.bat and system Gradle is unavailable.' }
    $wakeGradle = $gradle.Source
}

$buildArgs = @{
    GradleLauncher = $wakeGradle
}
if (-not $SkipClean) { $buildArgs.Clean = $true }

Run-Step 'Kotlin Display Windows native/JVM build and tests' {
    & (Join-Path $PSScriptRoot 'build-windows.ps1') @buildArgs
}

Run-Step 'Wake desktop unit/compile baseline' {
    Push-Location $WakeDir
    try {
        & $wakeGradle ':core:jvmTest' ':components:jvmTest' ':desktop:test' ':desktop:compileKotlin'
    } finally {
        Pop-Location
    }
}

$compatArgs = @{
    Repository = $WakeDir
    Mode = 'Compat'
    Backend = $Backend
    Target = ':desktop'
    StrictRenderer = $true
    SkipRuntime = $true
}
Run-Step 'Wake strict takeover compatibility preflight' {
    & (Join-Path $PSScriptRoot 'kd-project.ps1') @compatArgs
}

$strictStatus = 'NOT LAUNCHED'
if ($LaunchStrict) {
    $runArgs = @{
        Repository = $WakeDir
        Mode = 'Run'
        Backend = $Backend
        Target = ':desktop'
        StrictRenderer = $true
        SkipRuntime = $true
    }
    Run-Step 'Wake strict live GPU takeover' {
        & (Join-Path $PSScriptRoot 'kd-project.ps1') @runArgs
    }

    $auditDir = Join-Path $root 'build/external-audit'
    $captureDir = Join-Path $root 'build/external-capture'
    $proofAudit = Get-ChildItem $auditDir -Filter '*.log' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    $proofCapture = Get-ChildItem $captureDir -Filter '*.kdcap' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $proofAudit) {
        throw 'Strict takeover launched but no renderer audit was produced.'
    }
    if (-not $proofCapture) {
        throw 'Strict takeover launched but no Compose capture was produced.'
    }

    $auditBody = Get-Content -Raw $proofAudit.FullName
    $captureBody = Get-Content -Raw $proofCapture.FullName
    $requiredAuditMarkers = @(
        'START ',
        'TRANSFORMED_SKIA_CANVAS ',
        'TRANSFORMED_SKIKO_FRAME ',
        'TRANSFORMED_SKIKO_CONTEXT ',
        'TRANSFORMED_PARAGRAPH ',
        'TRANSFORMED_PARAGRAPH_BUILDER ',
        'EMBEDDED_HOST ',
        'ACTIVE_BACKEND ',
        'NATIVE_GLYPH_ATLAS_UPLOADED ',
        'TAKEOVER_PRESENTED ',
        'RETAINED_FRAME_PRESENTED '
    )
    foreach ($marker in $requiredAuditMarkers) {
        if (-not $auditBody.Contains($marker)) {
            throw "Strict takeover proof missing audit marker '$marker' in $($proofAudit.FullName)"
        }
    }
    if ($auditBody.Contains('STRICT_FAIL')) {
        throw "Strict takeover audit contains STRICT_FAIL: $($proofAudit.FullName)"
    }
    if (-not $captureBody.Contains('FRAME_BEGIN ')) {
        throw "Strict takeover capture contains no intercepted frames: $($proofCapture.FullName)"
    }
    if (-not $captureBody.Contains('PARAGRAPH ')) {
        throw "Strict takeover capture contains no intercepted paragraph text: $($proofCapture.FullName)"
    }

    $skikoVersion = $null
    $composeVersion = $null
    foreach ($line in ($auditBody -split [Environment]::NewLine)) {
        if (-not $skikoVersion -and $line -match 'RUNTIME_SOURCE class=org\.jetbrains\.skia\.Canvas source=.*skiko-awt-([0-9.]+)\.jar') {
            $skikoVersion = $Matches[1]
        }
        if (-not $composeVersion -and $line -match 'RUNTIME_SOURCE class=androidx\.compose\.ui\..* source=.*(?:ui-desktop|desktop-jvm)-([0-9.]+)\.jar') {
            $composeVersion = $Matches[1]
        }
    }

    if (-not $skikoVersion) {
        throw "Strict takeover could not identify the loaded Skiko runtime from $($proofAudit.FullName)"
    }
    if (@('0.9.4.2', '0.144.6') -notcontains $skikoVersion) {
        throw "Loaded Skiko runtime $skikoVersion is not source-verified for strict takeover."
    }
    if (-not $composeVersion) {
        throw "Strict takeover could not identify the loaded Compose desktop renderer from $($proofAudit.FullName)"
    }
    if (@('1.8.2', '1.11.1') -notcontains $composeVersion) {
        throw "Loaded Compose desktop renderer $composeVersion is not source-verified for strict takeover."
    }

    Write-Host ''
    Write-Host 'Kotlin Display live takeover proof'
    Write-Host '----------------------------------'
    Write-Host 'Skia Canvas transformer   VERIFIED'
    Write-Host 'Skiko frame transformer  VERIFIED'
    Write-Host 'Legacy Skiko swap bypass VERIFIED'
    Write-Host 'Paragraph transformer    VERIFIED'
    Write-Host 'Embedded Skiko host      VERIFIED'
    Write-Host 'Active GPU backend       VERIFIED'
    Write-Host 'Native glyph atlas       VERIFIED'
    Write-Host 'Captured paragraph text  VERIFIED'
    Write-Host 'Captured Compose frame   VERIFIED'
    Write-Host 'Native frame presented   VERIFIED'
    Write-Host 'Dirty frame retained     VERIFIED'
    Write-Host "Compose renderer         $composeVersion VERIFIED"
    Write-Host "Skiko runtime            $skikoVersion VERIFIED"
    Write-Host "Audit                    $($proofAudit.FullName)"
    Write-Host "Capture                  $($proofCapture.FullName)"
    $strictStatus = "VERIFIED (Compose $composeVersion / Skiko $skikoVersion)"
}

$auditDir = Join-Path $root 'build/external-audit'
$captureDir = Join-Path $root 'build/external-capture'
$latestAudit = Get-ChildItem $auditDir -Filter '*.log' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
$latestCapture = Get-ChildItem $captureDir -Filter '*.kdcap' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

$hostText = "$([Environment]::OSVersion.VersionString) / $env:PROCESSOR_ARCHITECTURE"
$auditText = if ($latestAudit) { $latestAudit.FullName } else { 'none' }
$captureText = if ($latestCapture) { $latestCapture.FullName } else { 'none' }

@"
# Wake Windows parity run

| Field | Value |
| --- | --- |
| Host | $hostText |
| Kotlin Display commit | $kdSha |
| Wake checkout | $WakeDir |
| Wake branch | $wakeBranch |
| Wake commit | $wakeSha |
| Backend | $Backend |
| Kotlin Display build/tests | **PASS** |
| Wake unit/compile baseline | **PASS** |
| Strict compatibility preflight | **PASS** |
| Strict live run | **$strictStatus** |
| Latest renderer audit | $auditText |
| Latest Compose capture | $captureText |

## Scope

This runner verifies the Windows native/JNI build, renderer/bridge/agent tests,
Wake's desktop compile/unit baseline, and strict supported Compose takeover preflight.

With -LaunchStrict it also runs Wake through the zero-edit renderer takeover.
The strict run is successful only if the application exits normally; unsupported
renderer operations terminate strict takeover rather than falling back to Skia.
"@ | Set-Content -Encoding UTF8 $report

Write-Host ''
Write-Host "Windows parity report: $report"
