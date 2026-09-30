param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Args
)

$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $root 'scripts/kanvas-project.ps1') @Args
exit $LASTEXITCODE
