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

$setupScript = Join-Path $root 'scripts\windows\setup.ps1'
$pythonOutput = & $setupScript -ProjectRoot $root -DataDirectory $data
$venvPython = @($pythonOutput)[-1]
if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
    throw 'The private Python environment was not created correctly.'
}

& (Join-Path $root 'scripts\windows\doctor.ps1') -ProjectRoot $root -DataDirectory $data

$env:FGO_LOCAL_HOME = $data
$env:FGO_LOCAL_CONTROL_PORT = "$port"
$env:FGO_LOCAL_OPEN_BROWSER = if ($openBrowser) { '1' } else { '0' }
$env:PYTHONUTF8 = '1'

Write-Host "Starting FgoGotran Local at $localUrl"
Write-Host 'Keep this window open while using local translation.'
& $venvPython -m fgogotran_local
exit $LASTEXITCODE
