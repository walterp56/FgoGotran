# Local sources only. No ADB, APK install, FGO writes or translation API calls.
[CmdletBinding()]
param([ValidateSet('JP','CN','TW','NA')][string[]]$Regions = @('JP','CN','TW','NA'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StoryAssetLibrary.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$result = Save-AtlasPrologueSnapshot -RepositoryRoot $repositoryRoot -Regions $Regions -Verbose:($VerbosePreference -ne 'SilentlyContinue')
Write-Output "Verified Atlas snapshot: $($result.Path)"
Write-Output "Files: $($result.FileCount); bytes: $($result.Bytes); reused identical snapshot: $($result.Reused)"
