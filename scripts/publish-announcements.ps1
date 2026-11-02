param(
    [string]$Title = "",
    [string]$Body = "",
    [string]$TitleEn = "",
    [string]$BodyEn = "",
    [string]$TitleZhHans = "",
    [string]$BodyZhHans = "",
    [string]$TitleZhHant = "",
    [string]$BodyZhHant = "",
    [ValidateSet("info", "success", "warning")]
    [string]$Level = "info",
    [string]$Date = "",
    [string]$Id = "",
    [string]$Remove = "",
    [switch]$List,
    [switch]$DryRun,
    [switch]$NoUpload,
    [switch]$Upload,
    [switch]$Verify,
    [string]$S3Uri = "s3://fgogotran-native-assets/catalog/catalog.json",
    [string]$AwsCli = "aws",
    [string]$Node = ""
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")
$AnnouncementsPath = Join-Path $RepoRoot "story_assets\cloud\host\announcements.json"
$CatalogPath = Join-Path $RepoRoot "release\native-assets\catalog\catalog.json"
$BuilderPath = Join-Path $RepoRoot "story_assets\tools\build-host-catalog.mjs"
$CatalogApi = "https://v4qqn1rvi1.execute-api.ap-east-1.amazonaws.com/prod/v1/native/catalog?lang=en&game=2.140.0&refresh=1"

if (-not $Date) {
    $Date = Get-Date -Format "yyyy-MM-dd"
}

# Backward-compatible aliases: -Title/-Body mean English.
if (-not $TitleEn -and $Title) { $TitleEn = $Title }
if (-not $BodyEn -and $Body) { $BodyEn = $Body }

function Get-Text {
    param($Value)
    if ($null -eq $Value) { return "" }
    if ($Value -is [string]) { return $Value }
    if ($Value.en) { return [string]$Value.en }
    if ($Value.'zh-Hans') { return [string]$Value.'zh-Hans' }
    if ($Value.'zh-Hant') { return [string]$Value.'zh-Hant' }
    return ""
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
    if ($null -eq $json) { $json = "[]" }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($AnnouncementsPath, "$json`n", $utf8)
}

function Resolve-Node {
    param([string]$Configured)

    if ($Configured) { return $Configured }
    $command = Get-Command node -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $bundled = Join-Path $env:USERPROFILE ".dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
    if (Test-Path $bundled) { return $bundled }
    throw "Node.js not found. Pass -Node C:\path\to\node.exe or add node to PATH."
}

$items = Read-Announcements

if ($List) {
    if ($items.Count -eq 0) {
        Write-Host "No announcements."
        exit 0
    }
    $items | ForEach-Object {
        "{0}`t{1}`t{2}`t{3}" -f $_.id, $_.date, $_.level, (Get-Text $_.title)
    }
    exit 0
}

if (-not $Remove -and -not $TitleEn) {
    Write-Host "Add or update an announcement (English is required; Chinese falls back to English if blank)."
    $TitleEn = Read-Host "English title"
    if (-not $TitleEn) { throw "English title is required." }
    $BodyEn = Read-Host "English body"
    if (-not $BodyEn) { throw "English body is required." }

    $TitleZhHans = Read-Host "简体中文标题 [Enter = English]"
    if (-not $TitleZhHans) { $TitleZhHans = $TitleEn }
    $BodyZhHans = Read-Host "简体中文内容 [Enter = English]"
    if (-not $BodyZhHans) { $BodyZhHans = $BodyEn }

    $TitleZhHant = Read-Host "繁體中文標題 [Enter = English]"
    if (-not $TitleZhHant) { $TitleZhHant = $TitleEn }
    $BodyZhHant = Read-Host "繁體中文內容 [Enter = English]"
    if (-not $BodyZhHant) { $BodyZhHant = $BodyEn }

    $levelInput = Read-Host "Level (info/success/warning) [info]"
    if ($levelInput) {
        if ($levelInput -notin @("info", "success", "warning")) {
            throw "Level must be info, success, or warning."
        }
        $Level = $levelInput
    }
}

if (-not $BodyEn -and -not $Remove) {
    $BodyEn = Read-Host "English body"
    if (-not $BodyEn) { throw "English body is required." }
}

# Fill missing translations with English so every language has text.
if (-not $TitleZhHans) { $TitleZhHans = $TitleEn }
if (-not $BodyZhHans) { $BodyZhHans = $BodyEn }
if (-not $TitleZhHant) { $TitleZhHant = $TitleEn }
if (-not $BodyZhHant) { $BodyZhHant = $BodyEn }

if ($Remove) {
    $before = $items.Count
    $items = @($items | Where-Object { $_.id -ne $Remove })
    if ($items.Count -eq $before) { throw "Announcement not found: $Remove" }
    Write-Host "Removed $Remove"
}
else {
    $slug = ([regex]::Replace($TitleEn.ToLowerInvariant(), "[^a-z0-9]+", "-")).Trim("-")
    if (-not $Id) {
        $Id = if ($slug) { "$Date-$slug" } else { "announcement-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
    }
    $entry = [pscustomobject]@{
        id    = $Id
        title = [pscustomobject]@{
            en        = $TitleEn
            'zh-Hans' = $TitleZhHans
            'zh-Hant' = $TitleZhHant
        }
        body  = [pscustomobject]@{
            en        = $BodyEn
            'zh-Hans' = $BodyZhHans
            'zh-Hant' = $BodyZhHant
        }
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
if ($LASTEXITCODE -ne 0) { throw "Catalogue build failed with exit code $LASTEXITCODE." }

if ($Upload -and -not $NoUpload) {
    if (-not $S3Uri) { throw "S3Uri is empty. Pass -S3Uri." }
    & $AwsCli s3 cp $CatalogPath $S3Uri --cache-control no-store --content-type application/json
    if ($LASTEXITCODE -ne 0) { throw "S3 upload failed with exit code $LASTEXITCODE." }
    Write-Host "Uploaded $S3Uri"
}
else {
    Write-Host "Local release ready: $CatalogPath"
    Write-Host "Upload manually:"
    Write-Host "  $AwsCli s3 cp `"$CatalogPath`" `"$S3Uri`" --cache-control no-store --content-type application/json"
}

if ($Verify) {
    $response = Invoke-RestMethod -Uri $CatalogApi
    Write-Host "API announcements: $($response.announcements.Count)"
    $response.announcements | ForEach-Object { "  [$($_.level)] $(Get-Text $_.title)" }
}
