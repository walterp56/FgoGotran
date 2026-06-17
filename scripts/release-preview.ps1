param(
    [string]$Python = "",
    [string]$Output = "",
    [string]$Locale = "zh-Hans",
    [string]$BaseUrl = "https://cdn.fgogotran.com",
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

function Publish-PreviewRelease {
    param(
        [string]$OutputRoot,
        [string]$PreviewLocale
    )

    if (-not $S3Uri.StartsWith("s3://")) {
        throw "S3Uri must start with s3://, for example s3://your-bucket"
    }

    $AwsCommand = Get-Command $AwsCli -ErrorAction SilentlyContinue
    if (-not $AwsCommand) {
        throw "AWS CLI not found: $AwsCli"
    }

    $PreviewDir = Join-Path $OutputRoot "preview\$PreviewLocale\latest"
    if (-not (Test-Path $PreviewDir)) {
        throw "Missing preview directory: $PreviewDir"
    }

    Write-Host ""
    Write-Host "Publishing preview CDN files to $($S3Uri.TrimEnd('/'))"

    & $AwsCli s3 cp $PreviewDir (Join-S3Path $S3Uri "preview/$PreviewLocale/latest/") `
        --recursive `
        --content-type "application/json; charset=utf-8" `
        --cache-control $NoCacheControl
    Assert-ExitCode "Upload preview files"

    if ($CloudFrontDistributionId) {
        Write-Host "Invalidating CloudFront path /preview/*"
        $InvalidationId = & $AwsCli cloudfront create-invalidation `
            --distribution-id $CloudFrontDistributionId `
            --paths "/preview/*" `
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
        Write-Warning "No CloudFront distribution id was provided. Existing edge caches may keep serving old preview files."
    }

    if (-not $SkipVerify) {
        foreach ($FileName in @("character_names.preview.json", "terms.preview.json")) {
            $PreviewUrl = "$($BaseUrl.TrimEnd('/'))/preview/$PreviewLocale/latest/$FileName?verify=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
            Write-Host "Verifying live preview $PreviewUrl"
            $Response = Invoke-WebRequest -Uri $PreviewUrl -Headers @{
                "Cache-Control" = "no-cache, no-store, must-revalidate, max-age=0, s-maxage=0"
                "Pragma" = "no-cache"
            }
            if (-not $Response.StatusCode -or $Response.StatusCode -ge 400) {
                throw "Live preview verification failed for $FileName"
            }
        }
        Write-Host "Verified live preview files"
    }
}

Push-Location $RepoRoot
try {
    $PreviewArgs = @(
        (Join-Path $RepoRoot "term_builder\py\package_web_previews.py"),
        "--output", $Output,
        "--locale", $Locale
    )

    Write-Host "Preview sources include:"
    Write-Host "  term_builder\character_names.tsv"
    Write-Host "  term_builder\term.tsv"

    & $PythonExe @PreviewArgs

    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "Generated preview CDN files under:"
    Write-Host "  $Output\preview\$Locale\latest"
    Write-Host ""
    Write-Host "Upload this S3 prefix:"
    Write-Host "  preview/$Locale/latest/"

    if ($S3Uri) {
        Publish-PreviewRelease -OutputRoot $Output -PreviewLocale $Locale
    }
    else {
        Write-Host ""
        Write-Host "Or publish automatically:"
        Write-Host "  .\scripts\release-preview.ps1 -S3Uri s3://YOUR_BUCKET -CloudFrontDistributionId YOUR_DISTRIBUTION_ID"
    }
}
finally {
    Pop-Location
}
