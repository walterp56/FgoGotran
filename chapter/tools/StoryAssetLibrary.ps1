# Shared desktop-only helpers. Dot-sourcing defines functions; it performs no I/O.
Set-StrictMode -Version Latest

function Assert-StoryLocalPath {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Root, [switch]$AllowRoot)
    $full = [IO.Path]::GetFullPath($Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $base = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $comparison = [StringComparison]::OrdinalIgnoreCase
    if (-not (($AllowRoot -and $full.Equals($base, $comparison)) -or
        $full.StartsWith($base + [IO.Path]::DirectorySeparatorChar, $comparison))) {
        throw "Local path must stay under $base"
    }
    # Reject junctions/symlinks in existing ancestors, including the root itself.
    $candidate = $full
    while ($candidate) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked local path refused: $candidate" }
        }
        $candidate = [IO.Path]::GetDirectoryName($candidate)
    }
    return $full
}

function Get-StoryAssetLayout {
    param([Parameter(Mandatory)][string]$RepositoryRoot)
    $repository = [IO.Path]::GetFullPath($RepositoryRoot)
    $root = Assert-StoryLocalPath (Join-Path $repository 'story_assets') $repository
    return [pscustomobject]@{
        Root = $root; Atlas = (Join-Path $root 'atlas'); Originals = (Join-Path $root 'jp-originals')
        Builds = (Join-Path $root 'builds'); Runs = (Join-Path $root 'runs')
        LegacyFixtures = (Join-Path $repository 'chapter/local-fixtures')
    }
}

function Initialize-StoryAssetLibrary {
    param([Parameter(Mandatory)][string]$RepositoryRoot)
    $layout = Get-StoryAssetLayout $RepositoryRoot
    foreach ($path in @($layout.Root, $layout.Atlas, $layout.Originals, $layout.Builds, $layout.Runs)) {
        $null = Assert-StoryLocalPath $path $layout.Root -AllowRoot
        [IO.Directory]::CreateDirectory($path) | Out-Null
    }
    return $layout
}

function Get-StoryByteHash {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}

function ConvertFrom-StoryUtf8 {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    # Decode for validation only. The original bytes are saved without normalisation.
    return [Text.UTF8Encoding]::new($false, $true).GetString($Bytes).TrimStart([char]0xfeff)
}

function Assert-AtlasStoryUrl {
    param([Parameter(Mandatory)][string]$Url)
    if ($Url -cnotmatch '^https://api\.atlasacademy\.io/nice/(JP|CN|TW|NA)/(quest/1000000|script/010000001[01])$' -and
        $Url -cnotmatch '^https://static\.atlasacademy\.io/(JP|CN|TW|NA)/Script/01/010000001[01]\.txt$') {
        throw "URL outside the reviewed Prologue allowlist: $Url"
    }
}

function Get-AtlasStoryBytes {
    param([Parameter(Mandatory)]$Client, [Parameter(Mandatory)][string]$Url, [Parameter(Mandatory)][int]$Limit)
    Assert-AtlasStoryUrl $Url
    $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(45))
    $response = $null; $stream = $null; $buffered = [IO.MemoryStream]::new()
    try {
        $response = $Client.GetAsync($Url, [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $timeout.Token).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 200) { throw "Atlas returned HTTP $([int]$response.StatusCode): $Url" }
        if ($response.Content.Headers.ContentLength -gt $Limit) { throw "Atlas response exceeds $Limit bytes: $Url" }
        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $buffer = New-Object byte[] 16384
        while (($count = $stream.ReadAsync($buffer, 0, $buffer.Length, $timeout.Token).GetAwaiter().GetResult()) -gt 0) {
            if ($buffered.Length + $count -gt $Limit) { throw "Atlas response exceeds $Limit bytes: $Url" }
            $buffered.Write($buffer, 0, $count)
        }
        return [pscustomobject]@{ Bytes = $buffered.ToArray(); FetchedAt = [DateTime]::UtcNow.ToString('o') }
    } finally {
        if ($stream) { $stream.Dispose() }
        if ($response) { $response.Dispose() }
        $buffered.Dispose(); $timeout.Dispose()
    }
}

function Save-AtlasPrologueSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [ValidateSet('JP','CN','TW','NA')][string[]]$Regions = @('JP','CN','TW','NA'),
        # Offline regression seam only; the public entry point exposes no URL/transport override.
        [scriptblock]$FetchBytes
    )
    $regionsOrdered = @($Regions | ForEach-Object { $_.ToUpperInvariant() } | Sort-Object)
    if ($regionsOrdered.Count -eq 0 -or @($regionsOrdered | Select-Object -Unique).Count -ne $regionsOrdered.Count) { throw 'Choose one or more distinct regions' }
    $layout = Initialize-StoryAssetLibrary $RepositoryRoot
    $snapshotId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
    $pending = Assert-StoryLocalPath (Join-Path $layout.Atlas ('.partial-' + $snapshotId)) $layout.Atlas
    [IO.Directory]::CreateDirectory($pending) | Out-Null
    $files = [Collections.Generic.List[object]]::new()
    $totalBytes = 0L
    $client = $null
    try {
        if (-not $FetchBytes) {
            Add-Type -AssemblyName System.Net.Http
            $handler = [Net.Http.HttpClientHandler]::new()
            $handler.AllowAutoRedirect = $false
            $client = [Net.Http.HttpClient]::new($handler)
            $client.DefaultRequestHeaders.UserAgent.ParseAdd('FgoGotran-ChapterSources/1.0')
            $FetchBytes = { param($url, $limit) Get-AtlasStoryBytes -Client $client -Url $url -Limit $limit }
        }
        function Save-Source([string]$RelativePath, [string]$Url, [int]$Limit, [string]$Region, [string]$Kind, [string]$ScriptId = '') {
            Assert-AtlasStoryUrl $Url
            $result = & $FetchBytes $Url $Limit
            $bytes = [byte[]]$result.Bytes
            if ($bytes.Length -eq 0 -or $bytes.Length -gt $Limit) { throw "Invalid response size: $Url" }
            $currentTotal = 0L
            foreach ($saved in $files) { $currentTotal += $saved.size_bytes }
            if ($currentTotal + $bytes.Length -gt 16777216) { throw 'Snapshot exceeds the 16 MiB total limit' }
            $decoded = ConvertFrom-StoryUtf8 $bytes
            if ($Kind -eq 'script') {
                # Some regional exports have leading blank lines. Never trim saved bytes.
                $contentStart = $decoded.TrimStart()
                if (-not ($contentStart.StartsWith([string][char]0xff04) -or $contentStart.StartsWith('[soundStopAll]'))) {
                    throw "Response is not the expected native line script: $Url"
                }
            } else { $null = ConvertFrom-Json -InputObject $decoded -ErrorAction Stop }
            $destination = Assert-StoryLocalPath (Join-Path $pending $RelativePath) $pending
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
            [IO.File]::WriteAllBytes($destination, $bytes)
            $entry = [ordered]@{
                path = $RelativePath; url = $Url; region = $Region; kind = $Kind; script_id = $ScriptId
                fetched_at_utc = $result.FetchedAt; size_bytes = $bytes.Length; sha256 = Get-StoryByteHash $bytes
            }
            $files.Add($entry)
            if ($Kind -ne 'script') { return (ConvertFrom-Json -InputObject $decoded) }
        }
        foreach ($region in $regionsOrdered) {
            Write-Verbose "Fetching $region Prologue sources"
            $quest = Save-Source "$region/quest-1000000.json" "https://api.atlasacademy.io/nice/$region/quest/1000000" 1048576 $region 'quest'
            if ($quest.id -ne 1000000 -or $quest.warId -ne 100) { throw "Unexpected Prologue quest identity: $region" }
            $mappedScripts = @($quest.phaseScripts | ForEach-Object { $_.scripts } | ForEach-Object { $_.scriptId })
            foreach ($scriptId in @('0100000010','0100000011')) {
                $sourceUrl = "https://static.atlasacademy.io/$region/Script/01/$scriptId.txt"
                if ($scriptId -notin $mappedScripts) { throw "Prologue no longer maps to $scriptId in $region" }
                $metadata = Save-Source "$region/$scriptId.json" "https://api.atlasacademy.io/nice/$region/script/$scriptId" 1048576 $region 'script_metadata' $scriptId
                if ($metadata.scriptId -cne $scriptId -or $metadata.script -cne $sourceUrl -or
                    $metadata.scriptSizeBytes -le 0 -or $metadata.scriptSizeBytes -gt 4194304 -or
                    1000000 -notin @($metadata.quests | ForEach-Object { $_.id })) {
                    throw "Script metadata identity/size mismatch: $region/$scriptId"
                }
                Save-Source "$region/$scriptId.txt" $sourceUrl 4194304 $region 'script' $scriptId
                $savedScript = $files[$files.Count - 1]
                $savedScript.advertised_size_bytes = $metadata.scriptSizeBytes
                $savedScript.size_validation = 'raw_bytes'
                if ($savedScript.size_bytes -ne $metadata.scriptSizeBytes) {
                    # Regional metadata may count LF text while static exports use CRLF.
                    # Verify that exact representation difference, without rewriting the original.
                    $raw = [IO.File]::ReadAllBytes((Join-Path $pending $savedScript.path))
                    $lfSize = [Text.Encoding]::UTF8.GetByteCount((ConvertFrom-StoryUtf8 $raw).Replace("`r`n", "`n"))
                    if ($lfSize -ne $metadata.scriptSizeBytes) { throw "Script size differs from metadata: $region/$scriptId" }
                    $savedScript.size_validation = 'utf8_lf_without_bom'
                }
            }
        }
        # Verify on disk before publishing. A failed/cancelled run has no source-index.json.
        foreach ($entry in $files) {
            $path = Assert-StoryLocalPath (Join-Path $pending $entry.path) $pending
            if ((Get-Item -LiteralPath $path).Length -ne $entry.size_bytes -or
                (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw 'Saved source verification failed' }
            $totalBytes += $entry.size_bytes
        }
        $index = [ordered]@{
            schema_version = 1; status = 'verified'; snapshot_id = $snapshotId
            created_at_utc = [DateTime]::UtcNow.ToString('o'); quest_id = 1000000; war_id = 100
            regions = $regionsOrdered; script_ids = @('0100000010','0100000011'); total_size_bytes = $totalBytes
            scope = 'Atlas text sources only; not JP device originals, an install package, or a chapter-completeness claim. Remote files are fetched individually, not an atomic upstream revision.'
            files = @($files.ToArray())
        }
        $indexText = ConvertTo-Json -InputObject $index -Depth 8
        # Identical content reuses an existing verified snapshot, after verifying its actual files.
        foreach ($existing in Get-ChildItem -LiteralPath $layout.Atlas -Directory | Where-Object { $_.Name -notlike '.partial-*' }) {
            $existingPath = Assert-StoryLocalPath $existing.FullName $layout.Atlas
            $existingIndex = Join-Path $existingPath 'source-index.json'
            if (-not (Test-Path -LiteralPath $existingIndex -PathType Leaf)) { continue }
            try {
                $null = Assert-StoryLocalPath $existingIndex $existingPath
                $old = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($existingIndex))
                if ($old.schema_version -ne 1 -or $old.status -ne 'verified' -or $old.quest_id -ne 1000000 -or
                    ($old.regions -join ',') -cne ($regionsOrdered -join ',') -or $old.files.Count -ne $files.Count) { continue }
                foreach ($entry in $files) {
                    $matched = @($old.files | Where-Object { $_.path -ceq $entry.path -and $_.url -ceq $entry.url -and $_.sha256 -ceq $entry.sha256 -and $_.size_bytes -eq $entry.size_bytes })
                    if ($matched.Count -ne 1) { throw 'Different source snapshot' }
                    $path = Assert-StoryLocalPath (Join-Path $existingPath $entry.path) $existingPath
                    if ((Get-Item -LiteralPath $path).Length -ne $entry.size_bytes -or
                        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw 'Existing snapshot is not intact' }
                }
            } catch { continue }
            # Only this invocation's validated partial directory is removed. Existing snapshots never change.
            $null = Assert-StoryLocalPath $pending $layout.Atlas
            Remove-Item -LiteralPath $pending -Recurse -Force
            return [pscustomobject]@{ Path = $existingPath; Reused = $true; FileCount = $files.Count; Bytes = $totalBytes }
        }
        $destination = Assert-StoryLocalPath (Join-Path $layout.Atlas $snapshotId) $layout.Atlas
        # Publish the marker last, after the directory move: interrupted moves remain unverified.
        [IO.Directory]::Move($pending, $destination)
        [IO.File]::WriteAllText((Join-Path $destination 'source-index.json'), $indexText, [Text.UTF8Encoding]::new($false))
        return [pscustomobject]@{ Path = $destination; Reused = $false; FileCount = $files.Count; Bytes = $totalBytes }
    } finally { if ($client) { $client.Dispose() } }
}
