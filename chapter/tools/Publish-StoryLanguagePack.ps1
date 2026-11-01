# Publishes reviewed local data only. No device, network, APK or translation API access.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Draft)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StoryAssetLibrary.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$mappingRoot = Join-Path $repositoryRoot 'story_assets/mappings'
$draftPath = Assert-StoryLocalPath ([IO.Path]::GetFullPath($Draft)) $mappingRoot
if (-not (Test-Path -LiteralPath $draftPath -PathType Container)) { throw 'Reviewed story draft directory not found' }
$wrapper = Join-Path $repositoryRoot 'gradlew.bat'
Push-Location $repositoryRoot
try {
    & $wrapper ':chapter:publishLocalStoryPack' '--offline' "-Pchapter.storyPackDraft=$draftPath"
    if ($LASTEXITCODE -ne 0) { throw "Story package publication failed (Gradle exit $LASTEXITCODE)" }
} finally { Pop-Location }
