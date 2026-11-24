param(
    [string]$ChapterId = "event-80639",
    [string]$Version = "",
    [string]$Node = "",
    [string]$Output = "",
    [string]$BaseUrl = "https://cdn.fgogotran.com",
    [string]$MinimumAppVersion = "4.1.0",
    [string]$S3Uri = "",
    [string]$AwsCli = "aws",
    [string]$CloudFrontDistributionId = "",
    [switch]$SkipVerify,
    [switch]$SkipBuild,
    # Rebuild only story/latest/catalog.json and story/latest/announcements.json.
    # No chapter database is packaged, versioned, or uploaded.
    [switch]$CatalogOnly
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")

function Resolve-Node {
    param([string]$Configured)

    if ($Configured) {
        return $Configured
    }

    $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
    if ($nodeCommand) {
        return $nodeCommand.Source
    }

    throw "Node.js not found. Pass -Node C:\path\to\node.exe"
}

$NodeExe = Resolve-Node $Node
if (-not $Output) {
    $Output = Join-Path $RepoRoot "release\cdn"
}
$NoCacheControl = "no-cache, no-store, must-revalidate, max-age=0, s-maxage=0"

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

function Update-StoryCdnInvalidation {
    if (-not $CloudFrontDistributionId) {
        Write-Warning "No CloudFront distribution id was provided. Existing edge caches may keep serving old story files."
        return
    }

    Write-Host "Invalidating CloudFront path /story/*"
    $InvalidationId = & $AwsCli cloudfront create-invalidation `
        --distribution-id $CloudFrontDistributionId `
        --paths "/story/*" `
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

# Announcement-only publish: catalogue plus announcement board, no chapter files.
function Publish-StoryCatalogOnly {
    param([string]$OutputRoot)

    if (-not $S3Uri.StartsWith("s3://")) {
        throw "S3Uri must start with s3://, for example s3://your-bucket"
    }

    $AwsCommand = Get-Command $AwsCli -ErrorAction SilentlyContinue
    if (-not $AwsCommand) {
        throw "AWS CLI not found: $AwsCli"
    }

    $Announcements = Join-Path $OutputRoot "story\latest\announcements.json"
    $Catalog = Join-Path $OutputRoot "story\latest\catalog.json"
    foreach ($file in @($Announcements, $Catalog)) {
        if (-not (Test-Path $file)) { throw "Missing story catalog file: $file" }
    }

    Write-Host ""
    Write-Host "Publishing story catalogue and announcements to $($S3Uri.TrimEnd('/'))"

    # Announcements first, catalogue last, so a client never sees a catalogue
    # newer than the board it belongs to.
    & $AwsCli s3 cp $Announcements (Join-S3Path $S3Uri "story/latest/announcements.json") `
        --content-type "application/json; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload story announcements.json"

    & $AwsCli s3 cp $Catalog (Join-S3Path $S3Uri "story/latest/catalog.json") `
        --content-type "application/json; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload story catalog.json"

    Update-StoryCdnInvalidation

    if (-not $SkipVerify) {
        $AnnouncementsUrl = "$($BaseUrl.TrimEnd('/'))/story/latest/announcements.json?verify=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        Write-Host "Verifying live announcements $AnnouncementsUrl"
        $Live = Invoke-RestMethod -Uri $AnnouncementsUrl -Headers @{
            "Cache-Control" = $NoCacheControl
            "Pragma" = "no-cache"
        }
        if ($null -eq $Live.announcements) {
            throw "Live announcements document has no announcements array"
        }
        Write-Host "Verified live announcements: $($Live.announcements.Count)"
    }
}

function Publish-StoryRelease {
    param(
        [string]$OutputRoot,
        [string]$Chapter,
        [string]$ContentVersion
    )

    if (-not $S3Uri.StartsWith("s3://")) {
        throw "S3Uri must start with s3://, for example s3://your-bucket"
    }

    $AwsCommand = Get-Command $AwsCli -ErrorAction SilentlyContinue
    if (-not $AwsCommand) {
        throw "AWS CLI not found: $AwsCli"
    }

    $ReleaseDir = Join-Path $OutputRoot "story\$Chapter\releases\$ContentVersion"
    $ReleaseDb = Join-Path $ReleaseDir "story.db"
    $ReleaseSha = Join-Path $ReleaseDir "story.db.sha256"
    $ChapterLatest = Join-Path $OutputRoot "story\$Chapter\latest.json"
    $Catalog = Join-Path $OutputRoot "story\latest\catalog.json"

    foreach ($file in @($ReleaseDb, $ReleaseSha, $ChapterLatest, $Catalog)) {
        if (-not (Test-Path $file)) { throw "Missing story release file: $file" }
    }

    Write-Host ""
    Write-Host "Publishing story CDN files to $($S3Uri.TrimEnd('/'))"

    # Versioned chapter files first, then the chapter manifest, then the shared
    # catalog last, so clients never see a catalog entry before its DB exists.
    & $AwsCli s3 cp $ReleaseDb (Join-S3Path $S3Uri "story/$Chapter/releases/$ContentVersion/story.db") `
        --content-type "application/octet-stream" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload story.db"

    & $AwsCli s3 cp $ReleaseSha (Join-S3Path $S3Uri "story/$Chapter/releases/$ContentVersion/story.db.sha256") `
        --content-type "text/plain; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload story.db.sha256"

    & $AwsCli s3 cp $ChapterLatest (Join-S3Path $S3Uri "story/$Chapter/latest.json") `
        --content-type "application/json; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload chapter latest.json"

    & $AwsCli s3 cp $Catalog (Join-S3Path $S3Uri "story/latest/catalog.json") `
        --content-type "application/json; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload story catalog.json"

    Update-StoryCdnInvalidation

    if (-not $SkipVerify) {
        $CatalogUrl = "$($BaseUrl.TrimEnd('/'))/story/latest/catalog.json?verify=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        Write-Host "Verifying live catalog $CatalogUrl"
        $LiveCatalog = Invoke-RestMethod -Uri $CatalogUrl -Headers @{
            "Cache-Control" = $NoCacheControl
            "Pragma" = "no-cache"
        }
        $LiveChapter = $LiveCatalog.chapters | Where-Object { $_.chapterId -eq $Chapter }
        if (-not $LiveChapter) {
            throw "Live catalog does not list chapter $Chapter"
        }
        if ($LiveChapter.contentVersion -ne $ContentVersion) {
            throw "Live catalog version mismatch for ${Chapter}: expected=$ContentVersion actual=$($LiveChapter.contentVersion)"
        }
        Write-Host "Verified live catalog $Chapter at version $($LiveChapter.contentVersion)"
    }
}

Push-Location $RepoRoot
try {
    if ($CatalogOnly) {
        Write-Host "Regenerating the story catalogue and announcement board"
        $CatalogJson = & $NodeExe (Join-Path $RepoRoot "story_builder\package-story-release.mjs") `
            --catalog-only --out $Output --base-url $BaseUrl --minimum-app-version $MinimumAppVersion
        Assert-ExitCode "package-story-release --catalog-only"
        $CatalogResult = ($CatalogJson | Out-String) | ConvertFrom-Json
        Write-Host "Chapters: $($CatalogResult.chaptersInCatalog), announcements: $($CatalogResult.announcements)"
        Write-Host "  $($CatalogResult.catalog)"
        Write-Host "  $($CatalogResult.announcementsFile)"

        if ($S3Uri) {
            Publish-StoryCatalogOnly -OutputRoot $Output
        }
        else {
            Write-Host ""
            Write-Host "Upload these files (announcements first, catalogue last):"
            Write-Host "  story/latest/announcements.json"
            Write-Host "  story/latest/catalog.json"
        }
        return
    }

    if (-not $SkipBuild) {
        Write-Host "Building chapter database for $ChapterId"
        & $NodeExe (Join-Path $RepoRoot "story_builder\build-story-db.mjs") $ChapterId
        Assert-ExitCode "build-story-db"
    }

    $PackageArgs = @(
        (Join-Path $RepoRoot "story_builder\package-story-release.mjs"),
        $ChapterId,
        "--out", $Output,
        "--base-url", $BaseUrl,
        "--minimum-app-version", $MinimumAppVersion
    )
    if ($Version) {
        $PackageArgs += @("--version", $Version)
    }

    $PackageJson = & $NodeExe @PackageArgs
    Assert-ExitCode "package-story-release"
    $PackageResult = ($PackageJson | Out-String) | ConvertFrom-Json
    $ContentVersion = $PackageResult.version

    Write-Host ""
    Write-Host "Generated story CDN files under:"
    Write-Host "  $Output\story\$ChapterId\releases\$ContentVersion"
    Write-Host "  $Output\story\$ChapterId\latest.json"
    Write-Host "  $Output\story\latest\catalog.json"
    Write-Host ""
    Write-Host "Upload these S3 prefixes:"
    Write-Host "  story/$ChapterId/releases/$ContentVersion/"
    Write-Host "  story/$ChapterId/latest.json"
    Write-Host "  story/latest/catalog.json  (upload last)"

    if ($S3Uri) {
        Publish-StoryRelease -OutputRoot $Output -Chapter $ChapterId -ContentVersion $ContentVersion
    }
    else {
        Write-Host ""
        Write-Host "Or publish automatically:"
        Write-Host "  .\scripts\release-story.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID"
    }
}
finally {
    Pop-Location
}
