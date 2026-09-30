param(
    [Parameter(Mandatory=$true, Position=0)][string]$Repository,
    [ValidateSet('Build','Run','Package','Doctor','Compat')][string]$Mode = 'Build',
    [ValidateSet('auto','vulkan','opengl','d3d9','metal','gdi')][string]$Backend = 'auto',
    [string]$Target = '',
    [string]$BuildTask = '',
    [string]$RunTask = '',
    [string]$PackageTask = '',
    [string]$Ref = '',
    [switch]$AuditRenderer,
    [switch]$NoAuditRenderer,
    [switch]$StrictRenderer,
    [switch]$CleanTarget,
    [switch]$SkipRuntime
)

$ErrorActionPreference = 'Stop'
$kanvasRoot = Split-Path -Parent $PSScriptRoot
$initScript = Join-Path $kanvasRoot 'integration/kanvas.init.gradle'

$isUrl = $Repository -match '^(https?://|git@)' -or (
    ($Repository -match '\.git$') -and -not (Test-Path $Repository)
)

if ($isUrl) {
    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) { throw "git is required to clone $Repository" }

    $leaf = ($Repository -split '/')[-1]
    $name = [IO.Path]::GetFileNameWithoutExtension($leaf)
    $safeRef = if ($Ref) { $Ref -replace '[^A-Za-z0-9._-]', '_' } else { 'default' }
    $cloneRoot = Join-Path $kanvasRoot 'build/external'
    New-Item -ItemType Directory -Path $cloneRoot -Force | Out-Null
    $repo = Join-Path $cloneRoot "$name-$safeRef"

    if (-not (Test-Path (Join-Path $repo '.git'))) {
        Write-Host "==> Cloning $Repository"
        if ($Ref) {
            & $git.Source clone --branch $Ref --single-branch $Repository $repo
        } else {
            & $git.Source clone $Repository $repo
        }
        if ($LASTEXITCODE -ne 0) { throw 'git clone failed.' }
    } else {
        Write-Host "==> Reusing cloned repository: $repo"
    }
} else {
    if ($Ref) {
        Write-Warning '--Ref is ignored for local checkouts; the adapter never switches local branches.'
    }
    $repo = (Resolve-Path $Repository).Path
}

if (
    -not (Test-Path (Join-Path $repo 'settings.gradle')) -and
    -not (Test-Path (Join-Path $repo 'settings.gradle.kts'))
) {
    throw "Not a Gradle repository: $repo"
}

$wrapper = Join-Path $repo 'gradlew.bat'
if (Test-Path $wrapper) {
    $gradle = $wrapper
} else {
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if (-not $cmd) { throw 'No gradlew.bat or system Gradle found in target repository.' }
    $gradle = $cmd.Source
}

if (-not $SkipRuntime) {
    $buildRuntime = Join-Path $PSScriptRoot 'build-runtime.ps1'
    & $buildRuntime -GradleLauncher $gradle
    if ($LASTEXITCODE -ne 0) { throw 'Kanvas runtime build failed.' }
}

$args = @(
    '-I', $initScript,
    "-Dkanvas.home=$kanvasRoot",
    "-Dkanvas.backend=$Backend"
)

$doAudit = $Mode -eq 'Run' -and -not $NoAuditRenderer
if ($AuditRenderer -or $StrictRenderer) { $doAudit = $true }

$needsAgent = $doAudit -or $Mode -eq 'Package'
if ($needsAgent) {
    $kanvasWrapper = Join-Path $kanvasRoot 'gradlew.bat'
    if (Test-Path $kanvasWrapper) {
        $kanvasGradle = $kanvasWrapper
    } elseif (Test-Path $wrapper) {
        $kanvasGradle = $wrapper
    } else {
        $kanvasGradleCmd = Get-Command gradle -ErrorAction SilentlyContinue
        $kanvasGradle = if ($kanvasGradleCmd) {
            $kanvasGradleCmd.Source
        } else {
            $gradle
        }
    }

    $agentJar = Join-Path $kanvasRoot 'integration-agent/build/libs/kanvas-agent.jar'
    if (-not (Test-Path $agentJar)) {
        & $kanvasGradle -p $kanvasRoot ':integration-agent:jar'
        if ($LASTEXITCODE -ne 0) { throw 'Kanvas agent build failed.' }
    }
    if (-not (Test-Path $agentJar)) {
        throw "Kanvas agent was not built: $agentJar"
    }

    $args += "-Dkanvas.agentJar=$agentJar"
}

if ($doAudit) {
    $auditDir = Join-Path $kanvasRoot 'build/external-audit'
    $captureDir = Join-Path $kanvasRoot 'build/external-capture'
    New-Item -ItemType Directory -Path $auditDir -Force | Out-Null
    New-Item -ItemType Directory -Path $captureDir -Force | Out-Null

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $name = Split-Path $repo -Leaf
    $auditPath = Join-Path $auditDir ("{0}-{1}.log" -f $name, $stamp)
    $capturePath = Join-Path $captureDir ("{0}-{1}.kdcap" -f $name, $stamp)

    $args += "-Dkanvas.auditPath=$auditPath"
    $args += "-Dkanvas.capturePath=$capturePath"
    $args += '-Dkanvas.capture=true'
    $args += '-Dkanvas.takeover=true'
    $args += "-Dkanvas.strictRenderer=$($StrictRenderer.IsPresent.ToString().ToLowerInvariant())"

    Write-Host "Renderer audit : $auditPath"
    Write-Host "Compose capture: $capturePath"
    Write-Host 'GPU takeover   : enabled'
}

if ($Target) { $args += "-Dkanvas.target=$Target" }
if ($BuildTask) { $args += "-Dkanvas.buildTask=$BuildTask" }
if ($RunTask) { $args += "-Dkanvas.runTask=$RunTask" }
if ($PackageTask) { $args += "-Dkanvas.packageTask=$PackageTask" }

$previousPath = $env:PATH
$runtimePaths = @(
    (Join-Path $kanvasRoot 'native/build'),
    (Join-Path $kanvasRoot 'native/build/Debug'),
    (Join-Path $kanvasRoot 'native/build/Release'),
    (Join-Path $kanvasRoot 'native/build/RelWithDebInfo'),
    (Join-Path $kanvasRoot 'native/build/MinSizeRel')
)
$gccCommand = Get-Command gcc -ErrorAction SilentlyContinue
if ($gccCommand) {
    $runtimePaths += (Split-Path -Parent $gccCommand.Source)
}
$runtimePaths = $runtimePaths |
    Where-Object { $_ -and (Test-Path $_) } |
    Select-Object -Unique
if ($runtimePaths.Count -gt 0) {
    $env:PATH = (($runtimePaths -join ';') + ';' + $env:PATH)
}

Push-Location $repo
try {
    if ($CleanTarget) {
        & $gradle @args clean
        if ($LASTEXITCODE -ne 0) { throw 'Target clean failed.' }
    }

    $task = switch ($Mode) {
        'Doctor' { 'kanvasDoctor' }
        'Compat' { 'kanvasCompatibility' }
        'Run' { 'kanvasRun' }
        'Package' { 'kanvasPackage' }
        default { 'kanvasBuild' }
    }

    Write-Host ''
    Write-Host 'Kanvas adapter'
    Write-Host "Target : $repo"
    Write-Host "Action : $task"
    Write-Host "Backend: $Backend"
    if ($Target) { Write-Host "Project: $Target" }
    Write-Host ''

    & $gradle '--no-configuration-cache' '-Dorg.gradle.configuration-cache=false' @args $task '--stacktrace'
    if ($LASTEXITCODE -ne 0) {
        throw "External Gradle action failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
    $env:PATH = $previousPath
}
