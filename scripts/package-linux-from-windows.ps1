[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$TargetPath,

    [string]$TargetProject = ":desktop",

    [string]$PackageTask = "",

    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Target = (Resolve-Path $TargetPath).Path

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $Root "build\linux-dist"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$Output = (Resolve-Path $OutputDirectory).Path

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required. Install/start Docker Desktop, then run this command again."
}

docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is installed but its engine is not running."
}

$Image = "kotlin-display-linux-builder:latest"
$DockerDir = Join-Path $Root "docker"
$Dockerfile = Join-Path $DockerDir "linux-package.Dockerfile"

Write-Host "==> Build/reuse Linux amd64 builder image"
$BuildArgs = @(
    "build",
    "--platform", "linux/amd64",
    "-f", $Dockerfile,
    "-t", $Image,
    $DockerDir
)
& docker @BuildArgs
if ($LASTEXITCODE -ne 0) {
    throw "Docker builder image failed."
}

$DockerArgs = @(
    "run", "--rm",
    "--platform", "linux/amd64",
    "-e", "KD_TARGET_PROJECT=$TargetProject",
    "-v", "kotlin-display-gradle-cache:/root/.gradle",
    "-v", "${Root}:/kd-src:ro",
    "-v", "${Target}:/target-src:ro",
    "-v", "${Output}:/out"
)

if (-not [string]::IsNullOrWhiteSpace($PackageTask)) {
    $DockerArgs += @("-e", "KD_PACKAGE_TASK=$PackageTask")
}

$DockerArgs += @(
    $Image,
    "bash",
    "-lc",
    "sed 's/\r$//' /kd-src/scripts/package-linux-portable.sh > /tmp/kd-package.sh && chmod +x /tmp/kd-package.sh && /tmp/kd-package.sh"
)

Write-Host "==> Build Linux portable distribution"
& docker @DockerArgs
if ($LASTEXITCODE -ne 0) {
    throw "Linux portable package build failed."
}

Write-Host ""
Write-Host "Done. Send the .tar.gz from:"
Write-Host "  $Output"
