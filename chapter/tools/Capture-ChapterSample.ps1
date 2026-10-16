# Device reads only. Output is confined to chapter/local-fixtures and is never installed.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory)][string]$Adb,
    [ValidatePattern('^[0-9a-f]{40}\.bin$')][string[]]$AssetFile = @()
)
$ErrorActionPreference = 'Stop'
$packageName = 'com.aniplex.fategrandorder'
$remoteRoot = "/sdcard/Android/data/$packageName/files/data/d713"
$manifestName = 'cfb1d36393fd67385e046b084b7cf7ed'
$moduleRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureRoot = Join-Path $moduleRoot 'local-fixtures'
$captureRoot = Join-Path $fixtureRoot ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))

function Invoke-AdbRead([string[]]$Arguments, [switch]$AllowEmpty) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    $code = $LASTEXITCODE
    $resultText = ($result | Out-String).Trim()
    if ($code -ne 0 -and -not ($AllowEmpty -and $code -eq 1 -and $resultText.Length -eq 0)) {
        throw "ADB read failed ($code): $resultText"
    }
    return $resultText
}
function Get-RemoteHash([string]$Name) {
    $result = Invoke-AdbRead @('shell', 'sha256sum', "$remoteRoot/$Name")
    if ($result -notmatch '^([0-9a-f]{64})\s') { throw "Invalid remote hash: $Name" }
    return $Matches[1]
}

if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'ADB executable not found' }
if ((Invoke-AdbRead @('get-state')) -ne 'device') { throw 'Selected device is not ready' }
if (Invoke-AdbRead @('shell', 'pidof', $packageName) -AllowEmpty) {
    throw 'Close FGO before capturing. This tool will not stop it for you.'
}
$packageInfo = Invoke-AdbRead @('shell', 'dumpsys', 'package', $packageName)
$versionName = [regex]::Match($packageInfo, 'versionName=([^\s]+)').Groups[1].Value
$versionCode = [regex]::Match($packageInfo, 'versionCode=(\d+)').Groups[1].Value
if (-not $versionName -or -not $versionCode) { throw 'Cannot verify installed JP FGO version' }
$sdk = Invoke-AdbRead @('shell', 'getprop', 'ro.build.version.sdk')
$beforeManifest = Get-RemoteHash $manifestName
$inventory = Invoke-AdbRead @('shell', 'ls', '-1', $remoteRoot)
$names = @($manifestName) + @($AssetFile | Select-Object -Unique)
$beforeHashes = @{}
foreach ($name in $names) { $beforeHashes[$name] = Get-RemoteHash $name }
foreach ($name in $names) {
    $sizeText = Invoke-AdbRead @('shell', 'stat', '-c', '%s', "$remoteRoot/$name")
    if ($sizeText -notmatch '^\d+$' -or [long]$sizeText -gt 67108864) { throw "Asset exceeds the 64 MiB capture limit: $name" }
}

# A unique new directory avoids overwriting previous captures, even on a failed run.
New-Item -ItemType Directory -Path $captureRoot | Out-Null
$files = @()
foreach ($name in $names) {
    $destination = Join-Path $captureRoot $name
    Invoke-AdbRead @('pull', "$remoteRoot/$name", $destination) | Write-Verbose
    $localHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $afterHash = Get-RemoteHash $name
    if ($localHash -ne $beforeHashes[$name] -or $localHash -ne $afterHash) {
        throw "Asset changed during capture: $name. This capture is invalid; no device file was changed."
    }
    $files += [ordered]@{ path=$name; sha256=$localHash; size_bytes=(Get-Item -LiteralPath $destination).Length }
}
if ((Get-RemoteHash $manifestName) -ne $beforeManifest) { throw 'Manifest changed during capture' }
if (Invoke-AdbRead @('shell', 'pidof', $packageName) -AllowEmpty) { throw 'FGO started during capture' }

$record = [ordered]@{
    schema_version=1; captured_at_utc=[DateTime]::UtcNow.ToString('o'); serial=$Serial
    package=$packageName; version_name=$versionName; version_code=$versionCode; android_sdk=$sdk
    remote_root=$remoteRoot; manifest_sha256=$beforeManifest; files=$files
    scope='Read-only asset capture. No account data, device writes, native playback or chapter-completeness claim.'
}
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $captureRoot 'capture.json') -Encoding utf8
# Only filenames are recorded, not the contents of other assets.
$inventory | Set-Content -LiteralPath (Join-Path $captureRoot 'installed-filenames.txt') -Encoding utf8
Write-Output "Verified capture: $captureRoot"
