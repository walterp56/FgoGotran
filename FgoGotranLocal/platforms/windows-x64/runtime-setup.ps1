param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot,

    [Parameter(Mandatory = $true)]
    [string]$DataDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Get-Item -LiteralPath $ProjectRoot -Force).FullName
$data = [System.IO.Path]::GetFullPath($DataDirectory)
$platformManifestPath = Join-Path $PSScriptRoot 'platform.json'
if (-not (Test-Path -LiteralPath $platformManifestPath -PathType Leaf)) {
    throw 'The Windows x64 platform manifest is missing.'
}
try {
    $platform = Get-Content -LiteralPath $platformManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    throw "The Windows x64 platform manifest is invalid: $($_.Exception.Message)"
}
if (
    [int]$platform.schemaVersion -ne 1 -or
    [string]$platform.id -ne 'windows-x64' -or
    [string]$platform.operatingSystem -ne 'windows' -or
    [string]$platform.architecture -ne 'x64'
) {
    throw 'The runtime setup script received an incompatible platform manifest.'
}
$platformId = [string]$platform.id
$runtimeRoot = Join-Path $data 'runtime'
$platformRuntimeRoot = Join-Path $runtimeRoot $platformId
$llamaRoot = Join-Path $platformRuntimeRoot 'llama.cpp'
$downloadRoot = Join-Path $data 'downloads'
$manifestPath = Join-Path $platformRuntimeRoot 'managed-runtime.json'
$legacyManifestPath = Join-Path $runtimeRoot 'managed-runtime.json'
$configPath = Join-Path $data 'config.json'

function Write-Step([string]$Message) {
    Write-Host "[FgoGotranLocal] $Message"
}

function Confirm-AutomaticSetup([string]$Question) {
    if ($env:FGO_LOCAL_AUTO_SETUP -eq '1') { return $true }
    if ($env:FGO_LOCAL_AUTO_SETUP -eq '0') { return $false }
    $answer = Read-Host "$Question [Y/n]"
    return [string]::IsNullOrWhiteSpace($answer) -or $answer.Trim().ToLowerInvariant() -in @('y', 'yes')
}

function Test-PathInside([string]$Parent, [string]$Candidate) {
    $parentFull = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    $candidateFull = [System.IO.Path]::GetFullPath($Candidate)
    return $candidateFull.StartsWith($parentFull, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-FullyQualifiedWindowsPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    return $Path -match '^[A-Za-z]:[\\/]' -or $Path -match '^\\\\[^\\/]+[\\/][^\\/]+'
}

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Get-ExistingSettings {
    $result = [ordered]@{
        LlamaConfigured = $false
        LlamaPath = ''
        LlamaPathStale = $false
    }
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { return [PSCustomObject]$result }
    try {
        $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $llamaPath = [string]$config.llamaServerPath
        $result.LlamaPath = $llamaPath
        if (-not [string]::IsNullOrWhiteSpace($llamaPath)) {
            try {
                $isAbsolute = Test-FullyQualifiedWindowsPath $llamaPath
                $hasExpectedName = [System.IO.Path]::GetFileName($llamaPath).Equals(
                    'llama-server.exe',
                    [System.StringComparison]::OrdinalIgnoreCase
                )
                $result.LlamaConfigured = $isAbsolute -and $hasExpectedName -and (Test-Path -LiteralPath $llamaPath -PathType Leaf)
                $result.LlamaPathStale = -not $result.LlamaConfigured
            } catch {
                $result.LlamaConfigured = $false
                $result.LlamaPathStale = $true
            }
        }
    } catch {
        Write-Warning 'config.json could not be read. Automatic setup will not overwrite it.'
        $result.LlamaConfigured = $true
    }
    return [PSCustomObject]$result
}

function Update-StaleLlamaConfigPath([string]$ExpectedOldPath, [string]$ReplacementPath) {
    if (-not (Test-PathInside $data $ReplacementPath)) {
        throw 'Refusing to adopt a managed llama-server outside the user data directory.'
    }
    if (
        -not (Test-Path -LiteralPath $ReplacementPath -PathType Leaf) -or
        -not [System.IO.Path]::GetFileName($ReplacementPath).Equals(
            'llama-server.exe',
            [System.StringComparison]::OrdinalIgnoreCase
        )
    ) {
        throw 'The replacement llama-server.exe could not be validated.'
    }

    $configItem = Get-Item -LiteralPath $configPath -Force
    if (($configItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Refusing to update config.json through a symbolic link or junction.'
    }
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $currentPath = [string]$config.llamaServerPath
    if (-not $currentPath.Equals($ExpectedOldPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'config.json changed during setup; the saved llama-server path was not replaced.'
    }

    $backupPath = "$configPath.bak"
    if (Test-Path -LiteralPath $backupPath) {
        $backupItem = Get-Item -LiteralPath $backupPath -Force
        if (($backupItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Refusing to replace config.json.bak through a symbolic link or junction.'
        }
        if ($backupItem.PSIsContainer) {
            throw 'Refusing to replace config.json.bak because it is not a regular file.'
        }
    }
    Copy-Item -LiteralPath $configPath -Destination $backupPath -Force

    $temporary = Join-Path $data "config.json.launcher-$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        $config.llamaServerPath = $ReplacementPath
        Write-Utf8NoBom $temporary ($config | ConvertTo-Json -Depth 100)
        Move-Item -LiteralPath $temporary -Destination $configPath -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            if (-not (Test-PathInside $data $temporary)) { throw 'Refusing to clean a temporary file outside user data.' }
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Get-ManagedManifest {
    foreach ($candidatePath in @($manifestPath, $legacyManifestPath)) {
        if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) { continue }
        try {
            $manifest = Get-Content -LiteralPath $candidatePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ([int]$manifest.version -ne 1) { continue }
            if (
                $manifest.PSObject.Properties['platformId'] -and
                -not [string]::IsNullOrWhiteSpace([string]$manifest.platformId) -and
                [string]$manifest.platformId -ne $platformId
            ) {
                continue
            }
            if ($manifest.llamaServerPath) {
                if (-not (Test-PathInside $data ([string]$manifest.llamaServerPath))) { continue }
                if (-not (Test-Path -LiteralPath ([string]$manifest.llamaServerPath) -PathType Leaf)) { continue }
            }
            return $manifest
        } catch {
            continue
        }
    }
    return $null
}

function Get-NvidiaCudaVersion {
    $nvidia = Get-Command 'nvidia-smi.exe' -ErrorAction SilentlyContinue
    if (-not $nvidia) { return $null }
    try {
        $output = & $nvidia.Source 2>$null | Out-String
        $match = [regex]::Match($output, 'CUDA(?: UMD)? Version:\s*(\d+)\.(\d+)')
        if ($match.Success) {
            return [Version]::new([int]$match.Groups[1].Value, [int]$match.Groups[2].Value)
        }
    } catch {
        return $null
    }
    return $null
}

function Get-Sha256FromAsset([object]$Asset) {
    $digest = [string]$Asset.digest
    if ($digest -match '^sha256:([0-9a-fA-F]{64})$') {
        return $Matches[1].ToLowerInvariant()
    }
    return ''
}

function Find-LlamaReleaseAssets {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $headers = @{
        Accept = 'application/vnd.github+json'
        'User-Agent' = 'FgoGotranLocal-bootstrap'
        'X-GitHub-Api-Version' = '2022-11-28'
    }
    Write-Step 'Checking official ggml-org/llama.cpp Windows releases...'
    $releaseResponse = Invoke-RestMethod -UseBasicParsing -Headers $headers -Uri 'https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=20'
    # Windows PowerShell 5.1 can preserve a REST JSON array as one nested object
    # inside @(...), especially under StrictMode. Pipe once to enumerate releases.
    $releases = @($releaseResponse | ForEach-Object { $_ })
    if ($releases.Count -eq 0) {
        throw 'The official llama.cpp release API returned no releases.'
    }
    $cudaLimit = Get-NvidiaCudaVersion

    $cpuFallback = $null
    foreach ($release in $releases) {
        if ($release.draft) { continue }
        $assets = @($release.assets)
        if ($cudaLimit) {
            $cudaCandidates = @()
            foreach ($asset in $assets) {
                $match = [regex]::Match([string]$asset.name, '^llama-.+-bin-win-cuda-(\d+\.\d+)-x64\.zip$')
                if (-not $match.Success -or -not (Get-Sha256FromAsset $asset)) { continue }
                $version = [Version]$match.Groups[1].Value
                if ($version -gt $cudaLimit) { continue }
                $companion = $assets | Where-Object {
                    [string]$_.name -eq "cudart-llama-bin-win-cuda-$($match.Groups[1].Value)-x64.zip" -and
                    -not [string]::IsNullOrWhiteSpace((Get-Sha256FromAsset $_))
                } | Select-Object -First 1
                if ($companion) {
                    $cudaCandidates += [PSCustomObject]@{
                        Version = $version
                        Main = $asset
                        Companion = $companion
                    }
                }
            }
            $best = $cudaCandidates | Sort-Object Version -Descending | Select-Object -First 1
            if ($best) {
                return [PSCustomObject]@{
                    Tag = [string]$release.tag_name
                    Backend = "cuda-$($best.Version)"
                    Main = $best.Main
                    Companion = $best.Companion
                }
            }
        }

        $cpu = $assets | Where-Object {
            [string]$_.name -match '^llama-.+-bin-win-cpu-x64\.zip$' -and
            -not [string]::IsNullOrWhiteSpace((Get-Sha256FromAsset $_))
        } | Select-Object -First 1
        if ($cpu -and -not $cpuFallback) {
            $cpuFallback = [PSCustomObject]@{
                Tag = [string]$release.tag_name
                Backend = 'cpu'
                Main = $cpu
                Companion = $null
            }
            if (-not $cudaLimit) { return $cpuFallback }
        }
    }
    if ($cpuFallback) { return $cpuFallback }
    throw 'No complete, SHA-256-addressed official llama.cpp Windows x64 release was found. Try again later or configure llama.cpp manually.'
}

function Test-VerifiedFile([string]$Path, [int64]$ExpectedSize, [string]$ExpectedSha256) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $item = Get-Item -LiteralPath $Path
    if ($ExpectedSize -gt 0 -and $item.Length -ne $ExpectedSize) { return $false }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    return $actual -eq $ExpectedSha256.ToLowerInvariant()
}

function Move-InvalidDownloadAside([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    if (-not (Test-PathInside $data $Path)) {
        throw 'Refusing to move a download outside the managed data directory.'
    }
    $destination = "$Path.invalid-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
    Move-Item -LiteralPath $Path -Destination $destination
}

function Invoke-VerifiedDownload(
    [string]$Url,
    [string]$Destination,
    [int64]$ExpectedSize,
    [string]$ExpectedSha256,
    [string[]]$AllowedHosts
) {
    $uri = [Uri]$Url
    if ($uri.Scheme -ne 'https' -or $uri.Host -notin $AllowedHosts) {
        throw "Refusing an unexpected download source: $($uri.Host)"
    }
    if (-not (Test-PathInside $data $Destination)) {
        throw 'Refusing a download destination outside the managed data directory.'
    }
    New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
    if (Test-VerifiedFile $Destination $ExpectedSize $ExpectedSha256) { return }
    if (Test-Path -LiteralPath $Destination) { Move-InvalidDownloadAside $Destination }

    $partial = "$Destination.part"
    if (Test-Path -LiteralPath $partial) {
        $partialSize = (Get-Item -LiteralPath $partial).Length
        if ($ExpectedSize -gt 0 -and $partialSize -gt $ExpectedSize) {
            Move-InvalidDownloadAside $partial
        }
    }
    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source --location --fail --retry 3 --retry-delay 2 --connect-timeout 20 --speed-limit 1024 --speed-time 60 --continue-at - --output $partial $Url
        $downloadExitCode = $LASTEXITCODE
        if ($downloadExitCode -eq 33 -and (Test-Path -LiteralPath $partial)) {
            Write-Warning 'The server rejected download resume; preserving the partial file and retrying from the beginning.'
            Move-InvalidDownloadAside $partial
            & $curl.Source --location --fail --retry 3 --retry-delay 2 --connect-timeout 20 --speed-limit 1024 --speed-time 60 --output $partial $Url
            $downloadExitCode = $LASTEXITCODE
        }
        if ($downloadExitCode -ne 0) { throw "Download failed: $Url" }
    } else {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial
    }
    if (-not (Test-VerifiedFile $partial $ExpectedSize $ExpectedSha256)) {
        Move-InvalidDownloadAside $partial
        throw 'Downloaded file failed its official size or SHA-256 check.'
    }
    Move-Item -LiteralPath $partial -Destination $Destination -Force
}

function Install-LlamaRuntime {
    $selection = Find-LlamaReleaseAssets
    if ($selection.Backend -eq 'cpu' -and (Get-NvidiaCudaVersion)) {
        Write-Warning 'No compatible verified CUDA package was found; the managed runtime will use the official CPU build.'
    }
    $assets = @($selection.Main)
    if ($selection.Companion) { $assets += $selection.Companion }
    foreach ($asset in $assets) {
        $safeName = [System.IO.Path]::GetFileName([string]$asset.name)
        if ($safeName -ne [string]$asset.name -or $safeName -notmatch '\.zip$') {
            throw 'The official release returned an unsafe asset name.'
        }
        $destination = Join-Path $downloadRoot $safeName
        Write-Step "Downloading verified llama.cpp asset: $safeName"
        Invoke-VerifiedDownload `
            -Url ([string]$asset.browser_download_url) `
            -Destination $destination `
            -ExpectedSize ([int64]$asset.size) `
            -ExpectedSha256 (Get-Sha256FromAsset $asset) `
            -AllowedHosts @('github.com')
    }

    New-Item -ItemType Directory -Path $llamaRoot -Force | Out-Null
    $safeTag = [regex]::Replace([string]$selection.Tag, '[^A-Za-z0-9._-]', '_')
    $safeBackend = [regex]::Replace([string]$selection.Backend, '[^A-Za-z0-9._-]', '_')
    $target = Join-Path $llamaRoot "$safeTag-$safeBackend"
    if (Test-Path -LiteralPath $target) {
        $existingServer = Get-ChildItem -LiteralPath $target -Filter 'llama-server.exe' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($existingServer) {
            return [PSCustomObject]@{
                ServerPath = $existingServer.FullName
                Backend = $selection.Backend
                Release = $selection.Tag
            }
        }
        $target = "$target-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
    }

    $staging = Join-Path $llamaRoot ".staging-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    try {
        foreach ($asset in $assets) {
            $archive = Join-Path $downloadRoot ([string]$asset.name)
            Expand-Archive -LiteralPath $archive -DestinationPath $staging -Force
        }
        $server = Get-ChildItem -LiteralPath $staging -Filter 'llama-server.exe' -File -Recurse | Select-Object -First 1
        if (-not $server) { throw 'The verified llama.cpp archives did not contain llama-server.exe.' }
        Move-Item -LiteralPath $staging -Destination $target
        $relativeServer = $server.FullName.Substring($staging.Length).TrimStart('\')
        $installedServer = Join-Path $target $relativeServer
        if (-not (Test-Path -LiteralPath $installedServer -PathType Leaf)) {
            throw 'llama.cpp extraction completed, but llama-server.exe could not be validated.'
        }
        return [PSCustomObject]@{
            ServerPath = $installedServer
            Backend = $selection.Backend
            Release = $selection.Tag
        }
    } finally {
        if (Test-Path -LiteralPath $staging) {
            if (-not (Test-PathInside $llamaRoot $staging)) { throw 'Refusing to clean a staging directory outside the managed runtime.' }
            Remove-Item -LiteralPath $staging -Recurse -Force
        }
    }
}

function Assert-AvailableDisk([int64]$RequiredBytes) {
    $drive = [System.IO.DriveInfo]::new([System.IO.Path]::GetPathRoot($data))
    if ($drive.AvailableFreeSpace -lt $RequiredBytes) {
        $requiredGiB = [Math]::Ceiling($RequiredBytes / 1GB)
        throw "Automatic setup needs at least $requiredGiB GiB free on $($drive.Name)."
    }
}

function Save-Manifest([object]$Existing, [object]$Llama) {
    $values = [ordered]@{
        version = 1
        platformId = $platformId
        managedAt = [DateTime]::UtcNow.ToString('o')
        llamaServerPath = ''
        backend = ''
        llamaRelease = ''
    }
    if ($Existing) {
        foreach ($name in @('llamaServerPath', 'backend', 'llamaRelease')) {
            if ($Existing.PSObject.Properties[$name]) { $values[$name] = [string]$Existing.$name }
        }
    }
    if ($Llama) {
        $values.llamaServerPath = [string]$Llama.ServerPath
        $values.backend = [string]$Llama.Backend
        $values.llamaRelease = [string]$Llama.Release
    }
    New-Item -ItemType Directory -Path $platformRuntimeRoot -Force | Out-Null
    $temporary = "$manifestPath.tmp"
    Write-Utf8NoBom $temporary ([PSCustomObject]$values | ConvertTo-Json)
    Move-Item -LiteralPath $temporary -Destination $manifestPath -Force
}

if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) {
    throw 'Automatic runtime setup supports 64-bit Windows only.'
}
if ($data.TrimEnd('\') -eq $root.TrimEnd('\')) {
    throw 'The data directory must not be the project root.'
}
New-Item -ItemType Directory -Path $data -Force | Out-Null

$settings = Get-ExistingSettings
$manifest = Get-ManagedManifest
$llama = $null
$replaceStaleLlamaPath = $false

if ($manifest) {
    if ($manifest.llamaServerPath) {
        $llama = [PSCustomObject]@{
            ServerPath = [string]$manifest.llamaServerPath
            Backend = [string]$manifest.backend
            Release = [string]$manifest.llamaRelease
        }
    }
}

if ($settings.LlamaPathStale) {
    $question = if ($llama) {
        'The configured llama-server.exe is missing. Use the existing verified managed runtime and replace only this stale setting?'
    } else {
        'The configured llama-server.exe is missing. Download a verified official replacement and replace only this stale setting?'
    }
    if (Confirm-AutomaticSetup $question) {
        if (-not $llama) {
            Assert-AvailableDisk 2GB
            $llama = Install-LlamaRuntime
        }
        $replaceStaleLlamaPath = $true
    } else {
        Write-Warning 'The stale llama-server setting was kept.'
    }
} elseif (-not $settings.LlamaConfigured -and -not $llama) {
    if (Confirm-AutomaticSetup 'llama.cpp is not configured. Download a verified official Windows x64 runtime automatically?') {
        Assert-AvailableDisk 2GB
        $llama = Install-LlamaRuntime
    } else {
        Write-Warning 'llama.cpp setup was skipped. Configure llama-server.exe in the browser.'
    }
}

if ($llama -or $manifest) {
    Save-Manifest $manifest $llama
    if ($replaceStaleLlamaPath) {
        Update-StaleLlamaConfigPath $settings.LlamaPath $llama.ServerPath
        Write-Step 'The stale llama-server path was replaced; model settings were left unchanged.'
    }
    Write-Step 'Managed runtime configuration is ready.'
}
