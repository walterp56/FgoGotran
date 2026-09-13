param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot,

    [Parameter()]
    [string]$DataDirectory,

    [Parameter()]
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Get-Item -LiteralPath $ProjectRoot -Force).FullName
$data = if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    Join-Path $root 'user_data'
} else {
    [System.IO.Path]::GetFullPath($DataDirectory)
}
if ($data.TrimEnd('\') -eq $root.TrimEnd('\')) {
    throw 'The user data directory must not be the project root.'
}
$nativeArchitecture = if (-not [string]::IsNullOrWhiteSpace($env:PROCESSOR_ARCHITEW6432)) {
    $env:PROCESSOR_ARCHITEW6432
} else {
    $env:PROCESSOR_ARCHITECTURE
}
if ($env:OS -ne 'Windows_NT') {
    throw 'This launcher currently supports Windows only.'
}
if ([string]::IsNullOrWhiteSpace($nativeArchitecture) -or $nativeArchitecture.ToUpperInvariant() -ne 'AMD64') {
    $displayArchitecture = if ([string]::IsNullOrWhiteSpace($nativeArchitecture)) { 'unknown' } else { $nativeArchitecture }
    throw "This package supports Windows x64 only. Detected native architecture: $displayArchitecture."
}
$platformRoot = Join-Path $root 'platforms\windows-x64'
$platformManifestPath = Join-Path $platformRoot 'platform.json'
if (-not (Test-Path -LiteralPath $platformManifestPath -PathType Leaf)) {
    throw "The Windows x64 platform manifest was not found: $platformManifestPath"
}
try {
    $platformManifest = Get-Content -LiteralPath $platformManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    throw "The Windows x64 platform manifest is not valid JSON: $($_.Exception.Message)"
}
if (
    [int]$platformManifest.schemaVersion -ne 1 -or
    [string]$platformManifest.id -ne 'windows-x64' -or
    [string]$platformManifest.operatingSystem -ne 'windows' -or
    [string]$platformManifest.architecture -ne 'x64' -or
    [string]::IsNullOrWhiteSpace([string]$platformManifest.pythonSetup) -or
    [string]::IsNullOrWhiteSpace([string]$platformManifest.runtimeSetup) -or
    [string]::IsNullOrWhiteSpace([string]$platformManifest.doctor)
) {
    throw 'The Windows x64 platform manifest is incompatible with this launcher.'
}

function Resolve-PlatformScript([string]$RelativePath) {
    if ([System.IO.Path]::IsPathRooted($RelativePath)) {
        throw "Platform script paths must be relative: $RelativePath"
    }
    $resolved = [System.IO.Path]::GetFullPath((Join-Path $platformRoot $RelativePath))
    $platformPrefix = $platformRoot.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($platformPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Platform script path escapes the platform package: $RelativePath"
    }
    return $resolved
}

$setupScript = Resolve-PlatformScript ([string]$platformManifest.pythonSetup)
$runtimeSetupScript = Resolve-PlatformScript ([string]$platformManifest.runtimeSetup)
$doctorScript = Resolve-PlatformScript ([string]$platformManifest.doctor)
foreach ($requiredScript in @($setupScript, $runtimeSetupScript, $doctorScript)) {
    if (-not (Test-Path -LiteralPath $requiredScript -PathType Leaf)) {
        throw "A required Windows x64 platform script was not found: $requiredScript"
    }
}
$port = 18081
if (-not [string]::IsNullOrWhiteSpace($env:FGO_LOCAL_CONTROL_PORT)) {
    $parsedPort = 0
    if ([int]::TryParse($env:FGO_LOCAL_CONTROL_PORT, [ref]$parsedPort) -and $parsedPort -ge 1024 -and $parsedPort -le 65535) {
        $port = $parsedPort
    }
}
$localUrl = "http://127.0.0.1:$port"
$openBrowser = (-not $NoBrowser) -and ($env:FGO_LOCAL_OPEN_BROWSER -ne '0')

function Test-LocalPortInUse([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $attempt = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $attempt.AsyncWaitHandle.WaitOne(400)) {
            return $false
        }
        $client.EndConnect($attempt)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

try {
    $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "$localUrl/healthz"
    $health = $response.Content | ConvertFrom-Json
    if ($response.StatusCode -eq 200 -and $health.ok -eq $true -and $health.service -eq 'fgogotran-local') {
        if ($openBrowser) { Start-Process $localUrl }
        Write-Host "FgoGotran Local is already running at $localUrl"
        exit 0
    }
} catch {
    # No matching FgoGotranLocal control service responded.
}
if (Test-LocalPortInUse $port) {
    throw "Control port $port is already used by another service. Close it or set FGO_LOCAL_CONTROL_PORT to another port."
}

$instanceMutex = New-Object System.Threading.Mutex($false, "Local\FgoGotranLocal-Control-$port")
$mutexAcquired = $false
try {
    try {
        $mutexAcquired = $instanceMutex.WaitOne(0)
    } catch [System.Threading.AbandonedMutexException] {
        $mutexAcquired = $true
    }
    if (-not $mutexAcquired) {
        throw 'Another FgoGotran Local launch is preparing the same control port. Wait for it to finish.'
    }

    $pythonOutput = & $setupScript -ProjectRoot $root -DataDirectory $data
    $venvPython = @($pythonOutput)[-1]
    if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
        throw 'The private Python environment was not created correctly.'
    }

    try {
        & $runtimeSetupScript -ProjectRoot $root -DataDirectory $data
    } catch {
        Write-Warning "Automatic llama.cpp setup did not complete: $($_.Exception.Message)"
        Write-Warning 'The control interface will still open so the runtime can be configured manually.'
    }

    & $doctorScript -ProjectRoot $root -DataDirectory $data

    $env:FGO_LOCAL_HOME = $data
    $env:FGO_LOCAL_PLATFORM_ID = [string]$platformManifest.id
    $env:FGO_LOCAL_CONTROL_PORT = "$port"
    $env:FGO_LOCAL_OPEN_BROWSER = if ($openBrowser) { '1' } else { '0' }
    $env:FGO_LOCAL_AUTO_START_MODEL = if ([string]::IsNullOrWhiteSpace($env:FGO_LOCAL_AUTO_START_MODEL)) { '1' } else { $env:FGO_LOCAL_AUTO_START_MODEL }
    $env:PYTHONUTF8 = '1'

    Write-Host "Starting FgoGotran Local at $localUrl"
    Write-Host 'Keep this window open while using local translation.'
    & $venvPython -m fgogotran_local
    $serviceExitCode = $LASTEXITCODE
} finally {
    if ($mutexAcquired) { [void]$instanceMutex.ReleaseMutex() }
    $instanceMutex.Dispose()
}
exit $serviceExitCode
