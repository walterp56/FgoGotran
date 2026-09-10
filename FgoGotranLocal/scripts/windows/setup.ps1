param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot,

    [Parameter(Mandatory = $true)]
    [string]$DataDirectory,

    [Parameter()]
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Get-Item -LiteralPath $ProjectRoot -Force).FullName
$data = [System.IO.Path]::GetFullPath($DataDirectory)
$venv = Join-Path $root '.venv'
$venvPython = Join-Path $venv 'Scripts\python.exe'
$marker = Join-Path $venv '.fgogotran-local-dependencies'

function Write-Step([string]$Message) {
    if (-not $Quiet) {
        Write-Host "[FgoGotranLocal] $Message"
    }
}

function Test-PythonCandidate([string]$Executable, [string[]]$PrefixArguments) {
    try {
        $versionText = & $Executable @PrefixArguments -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $versionText) {
            return $null
        }
        $parts = $versionText.Trim().Split('.')
        if ([int]$parts[0] -eq 3 -and [int]$parts[1] -ge 11 -and [int]$parts[1] -le 13) {
            return [PSCustomObject]@{
                Executable = $Executable
                PrefixArguments = $PrefixArguments
                Version = $versionText.Trim()
            }
        }
    } catch {
        return $null
    }
    return $null
}

function Find-CompatiblePython {
    if (-not [string]::IsNullOrWhiteSpace($env:FGO_LOCAL_PYTHON)) {
        $candidate = Test-PythonCandidate $env:FGO_LOCAL_PYTHON @()
        if ($candidate) { return $candidate }
    }

    $pyLauncher = Get-Command 'py.exe' -ErrorAction SilentlyContinue
    if ($pyLauncher) {
        foreach ($selector in @('-3.13', '-3.12', '-3.11')) {
            $candidate = Test-PythonCandidate $pyLauncher.Source @($selector)
            if ($candidate) { return $candidate }
        }
    }

    $python = Get-Command 'python.exe' -ErrorAction SilentlyContinue
    if ($python) {
        $candidate = Test-PythonCandidate $python.Source @()
        if ($candidate) { return $candidate }
    }
    return $null
}

function Get-DependencyFingerprint([string[]]$Paths) {
    $builder = New-Object System.Text.StringBuilder
    foreach ($path in $Paths) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            [void]$builder.Append([System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8))
            [void]$builder.Append("`n---`n")
        }
    }
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($builder.ToString())
        return ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '')
    } finally {
        $sha256.Dispose()
    }
}

if ($env:OS -ne 'Windows_NT') {
    throw 'This launcher currently supports Windows only.'
}
if (-not (Test-Path -LiteralPath (Join-Path $root 'pyproject.toml') -PathType Leaf)) {
    throw "pyproject.toml was not found in $root"
}

if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
    $python = Find-CompatiblePython
    if (-not $python) {
        throw 'Python 3.11, 3.12, or 3.13 (64-bit) is required. Install it from python.org, or set FGO_LOCAL_PYTHON to python.exe.'
    }
    Write-Step "Creating the private Python environment with Python $($python.Version)..."
    & $python.Executable @($python.PrefixArguments) -m venv $venv
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to create .venv.'
    }
}

$dependencyFiles = @(
    (Join-Path $root 'requirements.txt'),
    (Join-Path $root 'pyproject.toml')
)
$dependencyFingerprint = "v1:$(Get-DependencyFingerprint $dependencyFiles)"
$installedFingerprint = if (Test-Path -LiteralPath $marker -PathType Leaf) {
    Get-Content -LiteralPath $marker -Raw
} else {
    ''
}

$environmentCheck = "import importlib.metadata as m; import fastapi, fgogotran_local, gradio, uvicorn; assert m.version('gradio') == '6.26.0'"
$environmentReady = $false
if ($dependencyFingerprint -eq $installedFingerprint) {
    & $venvPython -c $environmentCheck 2>$null
    $environmentReady = $LASTEXITCODE -eq 0
}

if (-not $environmentReady) {
    Write-Step 'Installing or updating the local control interface...'
    & $venvPython -m pip install --disable-pip-version-check -e $root
    if ($LASTEXITCODE -ne 0) {
        throw 'Dependency installation failed. Check the Internet connection and run Start-FgoGotranLocal.cmd again.'
    }
    Set-Content -LiteralPath $marker -Value $dependencyFingerprint -NoNewline -Encoding UTF8
}

New-Item -ItemType Directory -Path $data -Force | Out-Null
Write-Step 'Environment is ready.'
Write-Output $venvPython
