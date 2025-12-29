# Check required governance files
Write-Host "Checking required governance files..."
$requiredFiles = @(
    "CONTRIBUTING.md",
    "CODE_OF_CONDUCT.md",
    "CODE_QUALITY_AND_SECURITY.md",
    "RELEASING.md",
    "CHANGELOG.md",
    "why-the-dco.md",
    ".github\pull_request_template.md"
)

foreach ($file in $requiredFiles) {
    if (-not (Test-Path $file)) {
        Write-Error "Missing required file: $file"
        exit 1
    }
}

# Check version vs CHANGELOG
Write-Host "Checking version vs CHANGELOG..."
if (Test-Path "gradle.properties") {
    $versionLine = Get-Content "gradle.properties" | Where-Object { $_ -match '^version=' }
    if ($versionLine) {
        $version = $versionLine -replace '^version=', ''
        $changelogContent = Get-Content "CHANGELOG.md" -Raw

        # Check if version exists in CHANGELOG or if it's unreleased
        $versionExists = $changelogContent -match [regex]::Escape($version)
        $hasUnreleased = $changelogContent -match '## \[Unreleased\]'

        if (-not $versionExists -and -not $hasUnreleased) {
            Write-Error "Current version $version not found in CHANGELOG.md and no [Unreleased] section found"
            exit 1
        }

        if (-not $versionExists -and $hasUnreleased) {
            Write-Host "Version $version will be released (found [Unreleased] section)" -ForegroundColor Yellow
        }
    }
}

Write-Host "Repo standards OK." -ForegroundColor Green
exit 0
