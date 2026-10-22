# No network, ADB or game data. Synthetic fixtures are kept under ignored story_assets/runs.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StoryAssetLibrary.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$layout = Initialize-StoryAssetLibrary $repositoryRoot
$testRoot = Join-Path $layout.Runs ('tool-tests-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$scriptText = "`n" + [char]0xff04 + ' synthetic' + "`r`n" + [char]0xff20 + 'A' + "`n" + 'test text'
$scriptBytes = $utf8.GetBytes($scriptText)
$script:toolRequestCount = 0
$fetch = {
    param($url, $limit)
    $script:toolRequestCount++
    if ($url -cmatch '/nice/(JP|CN|TW|NA)/quest/1000000$') {
        $body = @{ id=1000000; warId=100; phaseScripts=@(@{phase=1; scripts=@(@{scriptId='0100000010'},@{scriptId='0100000011'})}) } | ConvertTo-Json -Depth 6 -Compress
        $bytes = $utf8.GetBytes($body)
    } elseif ($url -cmatch '/nice/(JP|CN|TW|NA)/script/(010000001[01])$') {
        $region=$Matches[1]; $id=$Matches[2]
        $advertisedSize = if ($region -in @('JP','NA')) { $utf8.GetByteCount($scriptText.Replace("`r`n", "`n")) } else { $scriptBytes.Length }
        $body = @{scriptId=$id; script="https://static.atlasacademy.io/$region/Script/01/$id.txt"; scriptSizeBytes=$advertisedSize; quests=@(@{id=1000000})} | ConvertTo-Json -Depth 5 -Compress
        $bytes=$utf8.GetBytes($body)
    } else { $bytes=$scriptBytes }
    return [pscustomobject]@{Bytes=$bytes; FetchedAt='2026-10-19T00:00:00Z'}
}
function Check([bool]$Condition, [string]$Message) { if (-not $Condition) { throw "FAIL: $Message" } }
function Must-Fail([scriptblock]$Action, [string]$Message) {
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    Check $failed $Message
}
function Completed-Count([string]$Root) {
    return @(Get-ChildItem -LiteralPath (Join-Path $Root 'story_assets/atlas') -Filter 'source-index.json' -Recurse -ErrorAction SilentlyContinue).Count
}

$first = Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $fetch
Check (-not $first.Reused -and $first.FileCount -eq 20 -and $script:toolRequestCount -eq 20) 'bounded 20-request, four-region snapshot'
$index = Get-Content -LiteralPath (Join-Path $first.Path 'source-index.json') -Raw | ConvertFrom-Json
Check ($index.files.Count -eq 20 -and $index.regions.Count -eq 4) 'source metadata coverage'
Check (($index.files | Where-Object { $_.kind -eq 'script' -and $_.region -eq 'JP' } | Select-Object -First 1).size_validation -eq 'utf8_lf_without_bom') 'verify canonical LF metadata without editing CRLF files'
Check ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $first.Path 'TW/0100000011.txt'))) -ceq [Convert]::ToBase64String($scriptBytes)) 'preserve raw UTF-8 and original line endings'
$indexHash = (Get-FileHash -LiteralPath (Join-Path $first.Path 'source-index.json')).Hash
$second = Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $fetch
Check ($second.Reused -and $second.Path -ceq $first.Path -and (Completed-Count $testRoot) -eq 1) 'reuse identical verified content'
Check ((Get-FileHash -LiteralPath (Join-Path $first.Path 'source-index.json')).Hash -ceq $indexHash) 'reuse does not rewrite provenance'

# Corrupted prior evidence is not overwritten or accepted as reusable.
[IO.File]::AppendAllText((Join-Path $first.Path 'TW/0100000011.txt'), 'corrupt')
$third = Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $fetch
Check (-not $third.Reused -and $third.Path -cne $first.Path -and (Completed-Count $testRoot) -eq 2) 'tampered prior files prevent reuse'
Check ([IO.File]::ReadAllText((Join-Path $first.Path 'TW/0100000011.txt')).EndsWith('corrupt')) 'do not overwrite old evidence'

$one = Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -Regions @('TW') -FetchBytes $fetch
Check ($one.FileCount -eq 5 -and -not $one.Reused) 'single-region requests stay bounded'
$completed = Completed-Count $testRoot
$failedFetch = { param($url,$limit) if ($url -like '*.txt') { throw 'simulated cancellation/network failure' }; & $fetch $url $limit }
Must-Fail { Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $failedFetch } 'failed download must stop'
Check ((Completed-Count $testRoot) -eq $completed) 'failed download is not published'
$htmlFetch = { param($url,$limit) if ($url -like '*.txt') { return [pscustomobject]@{Bytes=$utf8.GetBytes('<html>error</html>');FetchedAt='test'} }; & $fetch $url $limit }
Must-Fail { Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $htmlFetch } 'reject HTML in place of native scripts'
$wrongIdentity = { param($url,$limit) if ($url -like '*/quest/*') { return [pscustomobject]@{Bytes=$utf8.GetBytes('{"id":2,"warId":100}');FetchedAt='test'} }; & $fetch $url $limit }
Must-Fail { Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $wrongIdentity } 'reject wrong quest identity'
$wrongSize = { param($url,$limit) if ($url -like '*.txt') { return [pscustomobject]@{Bytes=$utf8.GetBytes($scriptText + 'extra');FetchedAt='test'} }; & $fetch $url $limit }
Must-Fail { Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $wrongSize } 'reject metadata size mismatch'
$invalidUtf8 = { param($url,$limit) if ($url -like '*.txt') { return [pscustomobject]@{Bytes=[byte[]]@(255,255);FetchedAt='test'} }; & $fetch $url $limit }
Must-Fail { Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $invalidUtf8 } 'reject invalid UTF-8'
$oversized = { param($url,$limit) return [pscustomobject]@{Bytes=(New-Object byte[] ($limit+1));FetchedAt='test'} }
Must-Fail { Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -FetchBytes $oversized } 'enforce byte limits independently of transport'
Check ((Completed-Count $testRoot) -eq $completed) 'invalid data never publishes a snapshot'
Must-Fail { Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -Regions @('JP','JP') -FetchBytes $fetch } 'reject duplicate regions'
Must-Fail { Save-AtlasPrologueSnapshot -RepositoryRoot $testRoot -Regions @('JP','jp') -FetchBytes $fetch } 'normalise region case before duplicate checks'
Must-Fail { Assert-AtlasStoryUrl 'http://static.atlasacademy.io/JP/Script/01/0100000011.txt' } 'HTTPS only'
Must-Fail { Assert-AtlasStoryUrl 'https://static.atlasacademy.io.evil.test/JP/Script/01/0100000011.txt' } 'exact host allowlist'
Must-Fail { Assert-AtlasStoryUrl 'https://static.atlasacademy.io/JP/Script/01/0100000011.txt?redirect=1' } 'no query overrides'
Must-Fail { Assert-AtlasStoryUrl 'https://static.atlasacademy.io/JP/Script/01/0100000012.txt' } 'no unrelated scripts'
Must-Fail { Assert-StoryLocalPath (Join-Path $testRoot '../outside') $testRoot } 'path traversal refused'

# Exercise the complete capture CLI against a read-only ADB double in a separate synthetic repo.
# This catches PowerShell's case-insensitive parameter/local-variable collisions as well as paths.
$copyTools = Join-Path $testRoot 'chapter/tools'
[IO.Directory]::CreateDirectory($copyTools) | Out-Null
foreach ($name in @('Capture-ChapterSample.ps1', 'StoryAssetLibrary.ps1')) {
    [IO.File]::Copy((Join-Path $PSScriptRoot $name), (Join-Path $copyTools $name))
}
$previousTestRoot = $env:FGO_CHAPTER_CAPTURE_TEST_ROOT
try {
    $env:FGO_CHAPTER_CAPTURE_TEST_ROOT = $testRoot
    $captureTool = Join-Path $copyTools 'Capture-ChapterSample.ps1'
    $fakeAdb = Join-Path $PSScriptRoot 'test-fixtures/AdbReadFixture.ps1'
    & $captureTool -Serial synthetic-test -Adb $fakeAdb -AssetFile @('0000000000000000000000000000000000000000.bin') | Out-Null
    & $captureTool -Serial synthetic-test -Adb $fakeAdb -Destination LegacyFixtures | Out-Null
    $modernCaptures = @(Get-ChildItem -LiteralPath (Join-Path $testRoot 'story_assets/jp-originals') -Filter capture.json -Recurse)
    $legacyCaptures = @(Get-ChildItem -LiteralPath (Join-Path $testRoot 'chapter/local-fixtures') -Filter capture.json -Recurse)
    Check ($modernCaptures.Count -eq 1 -and $legacyCaptures.Count -eq 1) 'capture supports both confined destinations'
    $capture = Get-Content -LiteralPath $modernCaptures[0].FullName -Raw | ConvertFrom-Json
    Check ($capture.files.Count -eq 2 -and $capture.version_name -eq 'synthetic') 'capture verifies and records originals'
    Check (-not ([IO.File]::ReadAllText((Join-Path $testRoot 'adb-read-calls.txt')) -match '(?m)^(push|install|shell (rm|mv|cp|am|chmod|chown))\b')) 'capture never mutates device state'
} finally { $env:FGO_CHAPTER_CAPTURE_TEST_ROOT = $previousTestRoot }
Write-Output "PASS: Story asset tools offline regressions. Synthetic evidence: $testRoot"
