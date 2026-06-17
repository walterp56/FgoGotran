param(
    [string]$Python = "",
    [string]$ApkDir = "",
    [string]$Output = "",
    [string]$BaseUrl = "https://cdn.fgogotran.com",
    [string]$ReleaseSlug = "",
    [string]$ApkName = "",
    [string[]]$Changelog = @("Signed release APK"),
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
if (-not $ApkDir) {
    $ApkDir = Join-Path $RepoRoot "app\release"
}
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

function Get-RelativeUrlPath {
    param([string]$Url)

    $Uri = [System.Uri]$Url
    return $Uri.AbsolutePath.TrimStart("/")
}

function Publish-AppRelease {
    param([string]$OutputRoot)

    if (-not $S3Uri.StartsWith("s3://")) {
        throw "S3Uri must start with s3://, for example s3://your-bucket"
    }

    $AwsCommand = Get-Command $AwsCli -ErrorAction SilentlyContinue
    if (-not $AwsCommand) {
        throw "AWS CLI not found: $AwsCli"
    }

    $LatestManifest = Join-Path $OutputRoot "app\android\latest\manifest.json"
    if (-not (Test-Path $LatestManifest)) {
        throw "Missing latest app manifest: $LatestManifest"
    }

    $Manifest = Get-Content -LiteralPath $LatestManifest -Raw | ConvertFrom-Json
    $ApkKey = Get-RelativeUrlPath $Manifest.apkUrl
    $ApkNameFromManifest = Split-Path $ApkKey -Leaf
    $ReleaseDir = Join-Path $OutputRoot (Split-Path $ApkKey -Parent)
    $ReleaseApk = Join-Path $ReleaseDir $ApkNameFromManifest
    $ReleaseSha = "$ReleaseApk.sha256"

    if (-not (Test-Path $ReleaseApk)) {
        throw "Missing packaged APK: $ReleaseApk"
    }
    if (-not (Test-Path $ReleaseSha)) {
        throw "Missing packaged APK checksum: $ReleaseSha"
    }

    Write-Host ""
    Write-Host "Publishing app CDN files to $($S3Uri.TrimEnd('/'))"

    & $AwsCli s3 cp $ReleaseApk (Join-S3Path $S3Uri $ApkKey) `
        --content-type "application/vnd.android.package-archive" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload APK"

    & $AwsCli s3 cp $ReleaseSha (Join-S3Path $S3Uri "$ApkKey.sha256") `
        --content-type "text/plain; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload APK checksum"

    & $AwsCli s3 cp $LatestManifest (Join-S3Path $S3Uri "app/android/latest/manifest.json") `
        --content-type "application/json; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload app latest manifest"

    if ($CloudFrontDistributionId) {
        Write-Host "Invalidating CloudFront path /app/*"
        $InvalidationId = & $AwsCli cloudfront create-invalidation `
            --distribution-id $CloudFrontDistributionId `
            --paths "/app/*" `
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
        Write-Warning "No CloudFront distribution id was provided. Existing edge caches may keep serving old app files."
    }

    if (-not $SkipVerify) {
        $ManifestUrl = "$($BaseUrl.TrimEnd('/'))/app/android/latest/manifest.json?verify=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        Write-Host "Verifying live app manifest $ManifestUrl"
        $LiveManifest = Invoke-RestMethod -Uri $ManifestUrl -Headers @{
            "Cache-Control" = "no-cache, no-store, must-revalidate, max-age=0, s-maxage=0"
            "Pragma" = "no-cache"
        }
        if ($LiveManifest.apkSha256 -ne $Manifest.apkSha256) {
            throw "Live app manifest sha mismatch: expected=$($Manifest.apkSha256) actual=$($LiveManifest.apkSha256)"
        }
        Write-Host "Verified live app manifest version $($LiveManifest.versionName)"
    }
}

$Args = @(
    (Join-Path $RepoRoot "term_builder\py\package_apk_release.py"),
    "--apk-dir", $ApkDir,
    "--output", $Output,
    "--base-url", $BaseUrl
)

if ($ReleaseSlug) {
    $Args += @("--release-slug", $ReleaseSlug)
}
if ($ApkName) {
    $Args += @("--apk-name", $ApkName)
}
foreach ($Item in $Changelog) {
    if ($Item) {
        $Args += @("--changelog", $Item)
    }
}

Push-Location $RepoRoot
try {
    & $PythonExe @Args
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "Generated app CDN files under:"
    Write-Host "  $Output\app\android"
    Write-Host ""
    Write-Host "Upload these S3 prefixes:"
    Write-Host "  app/android/releases/<version>/"
    Write-Host "  app/android/latest/manifest.json  (upload last)"

    if ($S3Uri) {
        Publish-AppRelease -OutputRoot $Output
    }
    else {
        Write-Host ""
        Write-Host "Or publish automatically:"
        Write-Host "  .\scripts\release-app.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID"
    }
}
finally {
    Pop-Location
}
