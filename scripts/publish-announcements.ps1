param(
    [string]$S3Uri = "s3://fgogotran-native-assets/catalog/catalog.json",
    [string]$AwsCli = "aws",
    [string]$Node = "",
    [switch]$Upload,
    [switch]$NoUpload,
    [switch]$Verify
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")
$AnnouncementsPath = Join-Path $RepoRoot "story_assets\cloud\host\announcements.json"
$CatalogPath = Join-Path $RepoRoot "release\native-assets\catalog\catalog.json"
$BuilderPath = Join-Path $RepoRoot "story_assets\tools\build-host-catalog.mjs"
$CatalogApi = "https://v4qqn1rvi1.execute-api.ap-east-1.amazonaws.com/prod/v1/native/catalog?lang=en&game=2.140.0&refresh=1"

if (-not (Test-Path $AnnouncementsPath)) {
    throw "Not found: $AnnouncementsPath"
}

$parsed = Get-Content -Raw -Encoding UTF8 $AnnouncementsPath | ConvertFrom-Json
$items = @($parsed | ForEach-Object { $_ })
if ($items.Count -eq 0) {
    throw "announcements.json is empty."
}

foreach ($item in $items) {
    if (-not $item.id) { throw "An announcement is missing id." }
    if ($null -eq $item.title) { throw "$($item.id): missing title." }
    if ($null -eq $item.body) { throw "$($item.id): missing body." }
    if (-not $item.date) { throw "$($item.id): missing date." }
    if ($item.level -notin @("info", "success", "warning")) {
        throw "$($item.id): level must be info, success, or warning."
    }
}

Write-Host "Validated $($items.Count) announcements."

function Resolve-Node {
    param([string]$Configured)

    if ($Configured) { return $Configured }
    $command = Get-Command node -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $bundled = Join-Path $env:USERPROFILE ".dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
    if (Test-Path $bundled) { return $bundled }
    throw "Node.js not found. Pass -Node C:\path\to\node.exe or add node to PATH."
}

$nodeExe = Resolve-Node $Node
& $nodeExe $BuilderPath
if ($LASTEXITCODE -ne 0) { throw "Catalogue build failed with exit code $LASTEXITCODE." }

if ($Upload -and -not $NoUpload) {
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
    $response.announcements | ForEach-Object {
        $title = if ($_.title -is [string]) { $_.title } else { $_.title.en }
        "  [$($_.level)] $title"
    }
}
