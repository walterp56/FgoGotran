param(
    [string]$Python = "",
    [string]$Output = "",
    [string]$BaseUrl = "https://cdn.fgogotran.com",
    [string]$ContentVersion = "",
    [string]$MinimumAppVersion = "1.0.0",
    [string]$ReleaseNotes = "FgoGotran terminology database update",
    [string]$S3Uri = "",
    [string]$AwsCli = "aws",
    [string]$CloudFrontDistributionId = "",
    [switch]$SkipVerify,
    [switch]$WithAtlas
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")

function Resolve-Python {
    param([string]$Configured)

    if ($Configured) {
        return $Configured
    }

    $bundled = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
    if (Test-Path $bundled) {
        return $bundled
    }

    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        return $pythonCommand.Source
    }

    $pyCommand = Get-Command py -ErrorAction SilentlyContinue
    if ($pyCommand) {
        return $pyCommand.Source
    }

    throw "Python not found. Pass -Python C:\path\to\python.exe"
}

$PythonExe = Resolve-Python $Python
if (-not $Output) {
    $Output = Join-Path $RepoRoot "release\cdn"
}
$NoCacheControl = "no-cache, no-store, must-revalidate, max-age=0, s-maxage=0"

function Get-NextContentVersion {
    param([string]$OutputRoot)

    $prefix = Get-Date -Format "yyyy.MM.dd"
    $releaseDir = Join-Path $OutputRoot "db\zh-Hans\releases"
    if (-not (Test-Path $releaseDir)) {
        return "$prefix.1"
    }

    $maxPatch = 0
    Get-ChildItem -Path $releaseDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Name -match "^$([regex]::Escape($prefix))\.(\d+)$") {
            $patch = [int]$Matches[1]
            if ($patch -gt $maxPatch) {
                $maxPatch = $patch
            }
        }
    }
    return "$prefix.$($maxPatch + 1)"
}

if (-not $ContentVersion) {
    $ContentVersion = Get-NextContentVersion $Output
    Write-Host "Using content version: $ContentVersion"
}

function Assert-ExitCode {
    param(
        [string]$Step
    )

    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

function Join-S3Path {
    param(
        [string]$Root,
        [string]$Path
    )

    return "$($Root.TrimEnd('/'))/$($Path.TrimStart('/'))"
}

function Publish-DbRelease {
    param(
        [string]$OutputRoot,
        [string]$Version
    )

    if (-not $S3Uri.StartsWith("s3://")) {
        throw "S3Uri must start with s3://, for example s3://your-bucket"
    }

    $AwsCommand = Get-Command $AwsCli -ErrorAction SilentlyContinue
    if (-not $AwsCommand) {
        throw "AWS CLI not found: $AwsCli"
    }

    $ReleaseDir = Join-Path $OutputRoot "db\zh-Hans\releases\$Version"
    $ReleaseDb = Join-Path $ReleaseDir "fgo_terms.db"
    $ReleaseSha = Join-Path $ReleaseDir "fgo_terms.db.sha256"
    $LatestManifest = Join-Path $OutputRoot "db\zh-Hans\latest\manifest.json"

    if (-not (Test-Path $ReleaseDb)) {
        throw "Missing packaged DB: $ReleaseDb"
    }
    if (-not (Test-Path $ReleaseSha)) {
        throw "Missing packaged DB checksum: $ReleaseSha"
    }
    if (-not (Test-Path $LatestManifest)) {
        throw "Missing latest manifest: $LatestManifest"
    }

    Write-Host ""
    Write-Host "Publishing DB CDN files to $($S3Uri.TrimEnd('/'))"

    & $AwsCli s3 cp $ReleaseDb (Join-S3Path $S3Uri "db/zh-Hans/releases/$Version/fgo_terms.db") `
        --content-type "application/octet-stream" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload fgo_terms.db"

    & $AwsCli s3 cp $ReleaseSha (Join-S3Path $S3Uri "db/zh-Hans/releases/$Version/fgo_terms.db.sha256") `
        --content-type "text/plain; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload fgo_terms.db.sha256"

    # Upload latest last so clients never see a manifest before its DB object exists.
    & $AwsCli s3 cp $LatestManifest (Join-S3Path $S3Uri "db/zh-Hans/latest/manifest.json") `
        --content-type "application/json; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload latest manifest"

    if ($CloudFrontDistributionId) {
        Write-Host "Invalidating CloudFront path /db/*"
        $InvalidationId = & $AwsCli cloudfront create-invalidation `
            --distribution-id $CloudFrontDistributionId `
            --paths "/db/*" `
            --query "Invalidation.Id" `
            --output text
        Assert-ExitCode "Create CloudFront invalidation"

        if ($InvalidationId) {
            Write-Host "Waiting for CloudFront invalidation $InvalidationId"
            & $AwsCli cloudfront wait invalidation-completed `
                --distribution-id $CloudFrontDistributionId `
                --id $InvalidationId
            Assert-ExitCode "Wait for CloudFront invalidation"
        }
    }
    else {
        Write-Warning "No CloudFront distribution id was provided. Existing edge caches may keep serving old DB files."
    }

    if (-not $SkipVerify) {
        $ManifestUrl = "$($BaseUrl.TrimEnd('/'))/db/zh-Hans/latest/manifest.json?verify=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        Write-Host "Verifying live manifest $ManifestUrl"
        $LiveManifest = Invoke-RestMethod -Uri $ManifestUrl -Headers @{
            "Cache-Control" = $NoCacheControl
            "Pragma" = "no-cache"
        }
        if ($LiveManifest.contentVersion -ne $Version) {
            throw "Live manifest version mismatch: expected=$Version actual=$($LiveManifest.contentVersion)"
        }
        Write-Host "Verified live DB manifest version $($LiveManifest.contentVersion)"
    }
}

Push-Location $RepoRoot
try {
    $IngestArgs = @(Join-Path $RepoRoot "term_builder\py\ingest_atlas.py")
    if (-not $WithAtlas) {
        $IngestArgs += "--skip-atlas"
    }
    Write-Host "DB sources include:"
    Write-Host "  term_builder\character_names.tsv"
    Write-Host "  term_builder\term.tsv"
    & $PythonExe @IngestArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    & $PythonExe (Join-Path $RepoRoot "term_builder\py\build_db.py")
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $PackageArgs = @(
        (Join-Path $RepoRoot "term_builder\py\package_release.py"),
        "--output", $Output,
        "--base-url", $BaseUrl,
        "--minimum-app-version", $MinimumAppVersion,
        "--release-notes", $ReleaseNotes
    )
    $PackageArgs += @("--content-version", $ContentVersion)

    & $PythonExe @PackageArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "Generated DB CDN files under:"
    Write-Host "  $Output\db\zh-Hans"
    Write-Host ""
    Write-Host "Upload these S3 prefixes:"
    Write-Host "  db/zh-Hans/releases/<contentVersion>/"
    Write-Host "  db/zh-Hans/latest/manifest.json  (upload last)"

    if ($S3Uri) {
        Publish-DbRelease -OutputRoot $Output -Version $ContentVersion
    }
    else {
        Write-Host ""
        Write-Host "Or publish automatically:"
        Write-Host "  .\scripts\release-db.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID"
    }
}
finally {
    Pop-Location
}
