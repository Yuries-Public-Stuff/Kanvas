param(
    [string]$TargetRepo = 'https://github.com/Yur-ie/Yur-ie-test12341246.git',
    [string]$TargetBranch = 'main',
    [switch]$IncludeDocs
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$exportId = [System.Guid]::NewGuid().ToString('N')
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
    'kanvas-export-' + $exportId
)
$exportBranch = 'kanvas-export-' + $exportId

function Run-Git {
    $gitArgs = @($args)

    & git @gitArgs
    if ($LASTEXITCODE -ne 0) {
        throw "git $($gitArgs -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Is-DocPath([string]$Path) {
    $normalized = $Path.Replace('\', '/')

    if ($normalized.StartsWith('docs/')) {
        return $true
    }

    return $normalized -in @(
        'README.md',
        'PUBLISHING.md',
        'TODO.md',
        'native/README.md'
    )
}

if (-not (Test-Path (Join-Path $root '.git'))) {
    throw "Not a Git repository: $root"
}

$sourceBranch = (git -C $root rev-parse --abbrev-ref HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Could not determine source branch.'
}

$sourceHead = (git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Could not resolve source HEAD.'
}

$paths = @(
    git -C $root ls-tree -r --name-only $sourceHead
)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not list source files.'
}

if (-not $IncludeDocs) {
    $paths = @($paths | Where-Object { -not (Is-DocPath $_) })
}

if ($paths.Count -eq 0) {
    throw 'No files selected for export.'
}

$groups = @{}

foreach ($path in $paths) {
    $sha = (
        git -C $root log -1 --format=%H $sourceHead -- $path
    ).Trim()

    if ($LASTEXITCODE -ne 0 -or -not $sha) {
        throw "Could not find latest commit for $path"
    }

    if (-not $groups.ContainsKey($sha)) {
        $timestamp = [int64](
            git -C $root show -s --format=%ct $sha
        ).Trim()

        $subject = (
            git -C $root show -s --format=%s $sha
        ).Trim()

        $message = (
            git -C $root show -s --format=%B $sha
        ).TrimEnd()

        $authorName = (
            git -C $root show -s --format=%an $sha
        ).Trim()

        $authorEmail = (
            git -C $root show -s --format=%ae $sha
        ).Trim()

        $authorDate = (
            git -C $root show -s --format=%aI $sha
        ).Trim()

        $groups[$sha] = [ordered]@{
            Sha = $sha
            Timestamp = $timestamp
            Subject = $subject
            Message = $message
            AuthorName = $authorName
            AuthorEmail = $authorEmail
            AuthorDate = $authorDate
            Paths = New-Object System.Collections.Generic.List[string]
        }
    }

    $groups[$sha].Paths.Add($path)
}

$orderedGroups = @(
    $groups.Values |
        Sort-Object Timestamp, Sha
)

Write-Host "Source       : $root"
Write-Host "Source branch: $sourceBranch"
Write-Host "Source HEAD  : $sourceHead"
Write-Host "Target       : $TargetRepo"
Write-Host "Target branch: $TargetBranch"
Write-Host "Files        : $($paths.Count)"
Write-Host "Commit groups: $($orderedGroups.Count)"
Write-Host ''

try {
    New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

    Write-Host '==> Creating isolated worktree'
    Run-Git -C $root worktree add --detach $tempRoot $sourceHead

    Push-Location $tempRoot
    try {
        Write-Host '==> Creating fresh history'
        Run-Git checkout --orphan $exportBranch

        # Start empty. Current files are re-added group by group.
        Run-Git rm -r -f --ignore-unmatch .

        foreach ($group in $orderedGroups) {
            $short = $group.Sha.Substring(0, 7)
            Write-Host (
                "commit {0}  {1}  [{2} file(s)]" -f
                $short,
                $group.Subject,
                $group.Paths.Count
            )

            foreach ($path in $group.Paths) {
                Run-Git checkout $sourceHead -- $path
            }

            Run-Git add -- $group.Paths.ToArray()

            $oldAuthorName = $env:GIT_AUTHOR_NAME
            $oldAuthorEmail = $env:GIT_AUTHOR_EMAIL
            $oldAuthorDate = $env:GIT_AUTHOR_DATE

            try {
                $env:GIT_AUTHOR_NAME = $group.AuthorName
                $env:GIT_AUTHOR_EMAIL = $group.AuthorEmail
                $env:GIT_AUTHOR_DATE = $group.AuthorDate

                $messageFile = Join-Path $tempRoot '.kanvas-commit-message.txt'
                [System.IO.File]::WriteAllText(
                    $messageFile,
                    $group.Message,
                    [System.Text.UTF8Encoding]::new($false)
                )

                Run-Git commit --no-gpg-sign -F $messageFile
                Remove-Item $messageFile -Force
            }
            finally {
                $env:GIT_AUTHOR_NAME = $oldAuthorName
                $env:GIT_AUTHOR_EMAIL = $oldAuthorEmail
                $env:GIT_AUTHOR_DATE = $oldAuthorDate
            }
        }

        Write-Host '==> Verifying current file set'

        $exported = @(
            git ls-tree -r --name-only HEAD
        )
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not list exported files.'
        }

        $expected = @($paths | Sort-Object)
        $actual = @($exported | Sort-Object)

        if (Compare-Object $expected $actual) {
            throw 'Exported file set does not match selected source files.'
        }

        Write-Host '==> Pushing fresh history'

        $remotes = @(git remote)
        if ($remotes -contains 'kanvas-export-target') {
            Run-Git remote remove kanvas-export-target
        }

        Run-Git remote add kanvas-export-target $TargetRepo
        Run-Git push --force kanvas-export-target "HEAD:refs/heads/$TargetBranch"

        Write-Host ''
        Write-Host 'Fresh Kanvas history exported successfully.'
        Write-Host "Target: $TargetRepo"
        Write-Host "Branch: $TargetBranch"
        Write-Host "Head  : $((git rev-parse HEAD).Trim())"
    }
    finally {
        Pop-Location
    }
}
finally {
    Write-Host '==> Cleaning temporary worktree'

    & git -C $root worktree remove --force $tempRoot 2>$null
    if (Test-Path $tempRoot) {
        Remove-Item $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
