param(
    [string]$Python = "",
    [string]$Output = "",
    [string]$BaseUrl = "https://cdn.fgogotran.com",
    [string]$ContentVersion = "",
    [string]$MinimumAppVersion = "2.0.0",
    [string]$ReleaseNotes = "FgoGotran voice data update",
    [string]$S3Uri = "",
    [string]$AwsCli = "aws",
    [string]$CloudFrontDistributionId = "",
    [switch]$SkipVerify
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
    $releaseDir = Join-Path $OutputRoot "voice\zh\releases"
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
    param([string]$Step)

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

function Publish-VoiceRelease {
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

    $ReleaseDir = Join-Path $OutputRoot "voice\zh\releases\$Version"
    $ReleasePackage = Join-Path $ReleaseDir "voice_data.zip"
    $ReleaseSha = Join-Path $ReleaseDir "voice_data.zip.sha256"
    $LatestManifest = Join-Path $OutputRoot "voice\zh\latest\manifest.json"

    if (-not (Test-Path $ReleasePackage)) {
        throw "Missing packaged voice data: $ReleasePackage"
    }
    if (-not (Test-Path $ReleaseSha)) {
        throw "Missing packaged voice checksum: $ReleaseSha"
    }
    if (-not (Test-Path $LatestManifest)) {
        throw "Missing latest voice manifest: $LatestManifest"
    }

    Write-Host ""
    Write-Host "Publishing voice CDN files to $($S3Uri.TrimEnd('/'))"

    & $AwsCli s3 cp $ReleasePackage (Join-S3Path $S3Uri "voice/zh/releases/$Version/voice_data.zip") `
        --content-type "application/zip" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload voice_data.zip"

    & $AwsCli s3 cp $ReleaseSha (Join-S3Path $S3Uri "voice/zh/releases/$Version/voice_data.zip.sha256") `
        --content-type "text/plain; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload voice_data.zip.sha256"

    # Upload latest last so clients never see a manifest before its package exists.
    & $AwsCli s3 cp $LatestManifest (Join-S3Path $S3Uri "voice/zh/latest/manifest.json") `
        --content-type "application/json; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload latest voice manifest"

    if ($CloudFrontDistributionId) {
        Write-Host "Invalidating CloudFront path /voice/*"
        $InvalidationId = & $AwsCli cloudfront create-invalidation `
            --distribution-id $CloudFrontDistributionId `
            --paths "/voice/*" `
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
        Write-Warning "No CloudFront distribution id was provided. Existing edge caches may keep serving old voice files."
    }

    if (-not $SkipVerify) {
        $ManifestUrl = "$($BaseUrl.TrimEnd('/'))/voice/zh/latest/manifest.json?verify=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        Write-Host "Verifying live voice manifest $ManifestUrl"
        $LiveManifest = Invoke-RestMethod -Uri $ManifestUrl -Headers @{
            "Cache-Control" = $NoCacheControl
            "Pragma" = "no-cache"
        }
        if ($LiveManifest.contentVersion -ne $Version) {
            throw "Live voice manifest version mismatch: expected=$Version actual=$($LiveManifest.contentVersion)"
        }
        Write-Host "Verified live voice manifest version $($LiveManifest.contentVersion)"
    }
}

Push-Location $RepoRoot
try {
    Write-Host "Voice data sources include:"
    Write-Host "  term_builder\voice_tune\character_voice_profiles_cn.tsv"
    Write-Host "  term_builder\jp_cn_name_map.tsv"

    $PackageArgs = @(
        (Join-Path $RepoRoot "term_builder\py\package_voice_release.py"),
        "--output", $Output,
        "--base-url", $BaseUrl,
        "--minimum-app-version", $MinimumAppVersion,
        "--release-notes", $ReleaseNotes,
        "--content-version", $ContentVersion
    )

    & $PythonExe @PackageArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "Generated voice CDN files under:"
    Write-Host "  $Output\voice\zh"
    Write-Host ""
    Write-Host "Upload these S3 prefixes:"
    Write-Host "  voice/zh/releases/<contentVersion>/"
    Write-Host "  voice/zh/latest/manifest.json  (upload last)"

    if ($S3Uri) {
        Publish-VoiceRelease -OutputRoot $Output -Version $ContentVersion
    }
    else {
        Write-Host ""
        Write-Host "Or publish automatically:"
        Write-Host "  .\scripts\release-voice.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID"
    }
}
finally {
    Pop-Location
}
