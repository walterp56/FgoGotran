param(
    [string]$Title = "",
    [string]$Body = "",
    [ValidateSet("info", "success", "warning")]
    [string]$Level = "info",
    [string]$Date = "",
    [string]$Id = "",
    [string]$Remove = "",
    [switch]$List,
    [switch]$DryRun,
    [switch]$NoUpload,
    [switch]$Verify,
    [string]$S3Uri = "s3://fgogotran-native-assets/catalog/v1/catalog.json",
    [string]$AwsCli = "aws",
    [string]$Node = ""
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")
$AnnouncementsPath = Join-Path $RepoRoot "story_assets\cloud\host\announcements.json"
$CatalogPath = Join-Path $RepoRoot "story_assets\cloud\host\catalog\v1\catalog.json"
$BuilderPath = Join-Path $RepoRoot "story_assets\tools\build-host-catalog.mjs"
$CatalogApi = "https://v4qqn1rvi1.execute-api.ap-east-1.amazonaws.com/prod/v1/native/catalog?lang=en&game=2.140.0&refresh=1"

if (-not $Date) {
    $Date = Get-Date -Format "yyyy-MM-dd"
}

function Read-Announcements {
    if (-not (Test-Path $AnnouncementsPath)) {
        return @()
    }
    $parsed = Get-Content -Raw -Encoding UTF8 $AnnouncementsPath | ConvertFrom-Json
    if ($null -eq $parsed) {
        return @()
    }
    return @($parsed)
}

function Write-Announcements {
    param([object[]]$Items)

    $json = @($Items) | ConvertTo-Json -Depth 8
    if ($null -eq $json) {
        $json = "[]"
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($AnnouncementsPath, "$json`n", $utf8)
}

function Resolve-Node {
    param([string]$Configured)

    if ($Configured) {
        return $Configured
    }
    $command = Get-Command node -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    $bundled = Join-Path $env:USERPROFILE ".dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
    if (Test-Path $bundled) {
        return $bundled
    }
    throw "Node.js not found. Pass -Node C:\path\to\node.exe or add node to PATH."
}

$items = Read-Announcements

if ($List) {
    if ($items.Count -eq 0) {
        Write-Host "No announcements."
        exit 0
    }
    $items | ForEach-Object {
        "{0}`t{1}`t{2}`t{3}" -f $_.id, $_.date, $_.level, $_.title
    }
    exit 0
}

if (-not $Remove -and -not $Title) {
    Write-Host "Add or update an announcement"
    $Title = Read-Host "Title"
    if (-not $Title) {
        throw "Title is required."
    }
    $Body = Read-Host "Body"
    if (-not $Body) {
        throw "Body is required."
    }
    $levelInput = Read-Host "Level (info/success/warning) [info]"
    if ($levelInput) {
        if ($levelInput -notin @("info", "success", "warning")) {
            throw "Level must be info, success, or warning."
        }
        $Level = $levelInput
    }
}

if (-not $Body -and -not $Remove) {
    $Body = Read-Host "Body"
    if (-not $Body) {
        throw "Body is required."
    }
}

if ($Remove) {
    $before = $items.Count
    $items = @($items | Where-Object { $_.id -ne $Remove })
    if ($items.Count -eq $before) {
        throw "Announcement not found: $Remove"
    }
    Write-Host "Removed $Remove"
}
else {
    $slug = ([regex]::Replace($Title.ToLowerInvariant(), "[^a-z0-9]+", "-")).Trim("-")
    if (-not $Id) {
        $Id = if ($slug) { "$Date-$slug" } else { "announcement-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
    }
    $entry = [pscustomobject]@{
        id    = $Id
        title = $Title
        body  = $Body
        date  = $Date
        level = $Level
    }
    $existing = @($items | Where-Object { $_.id -eq $Id })
    if ($existing.Count -gt 0) {
        $items = @($items | ForEach-Object { if ($_.id -eq $Id) { $entry } else { $_ } })
    }
    else {
        $items = @($entry) + $items
    }
    Write-Host "Upserted $Id"
}

if ($DryRun) {
    Write-Host "Dry run; nothing written."
    @($items) | ConvertTo-Json -Depth 8
    exit 0
}

Write-Announcements -Items $items
Write-Host "Wrote $AnnouncementsPath ($($items.Count) announcements)"

$nodeExe = Resolve-Node $Node
& $nodeExe $BuilderPath
if ($LASTEXITCODE -ne 0) {
    throw "Catalogue build failed with exit code $LASTEXITCODE."
}

if (-not $NoUpload) {
    if (-not $S3Uri) {
        throw "S3Uri is empty. Pass -S3Uri or use -NoUpload."
    }
    & $AwsCli s3 cp $CatalogPath $S3Uri --cache-control no-store --content-type application/json
    if ($LASTEXITCODE -ne 0) {
        throw "S3 upload failed with exit code $LASTEXITCODE."
    }
    Write-Host "Uploaded $S3Uri"
}
else {
    Write-Host "Skipped upload (-NoUpload). Local catalogue: $CatalogPath"
}

if ($Verify) {
    $response = Invoke-RestMethod -Uri $CatalogApi
    Write-Host "API announcements: $($response.announcements.Count)"
    $response.announcements | ForEach-Object { "  [$($_.level)] $($_.title)" }
}
