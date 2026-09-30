param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Args
)

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root 'scripts/kanvas-project.ps1') @Args
exit $LASTEXITCODE
