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
$runtimeRoot = Join-Path $data 'runtime\windows-x64'
$managedPythonRoot = Join-Path $runtimeRoot 'python-3.13.15'
$managedPython = Join-Path $managedPythonRoot 'python.exe'
$legacyManagedPython = Join-Path $data 'runtime\python-3.13.15\python.exe'
$downloadDirectory = Join-Path $data 'downloads'
$pythonInstaller = Join-Path $downloadDirectory 'python-3.13.15-amd64.exe'
$pythonInstallerUrl = 'https://www.python.org/ftp/python/3.13.15/python-3.13.15-amd64.exe'
$pythonInstallerSha256 = 'edec09c4853aeae9ac36efb8c9f95b6b8e2fee65eee56d9767a8b7c69c574403'

function Write-Step([string]$Message) {
    if (-not $Quiet) {
        Write-Host "[FgoGotranLocal] $Message"
    }
}

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Confirm-AutomaticSetup([string]$Question) {
    if ($env:FGO_LOCAL_AUTO_SETUP -eq '1') { return $true }
    if ($env:FGO_LOCAL_AUTO_SETUP -eq '0') { return $false }
    $answer = Read-Host "$Question [Y/n]"
    return [string]::IsNullOrWhiteSpace($answer) -or $answer.Trim().ToLowerInvariant() -in @('y', 'yes')
}

function Test-PythonCandidate([string]$Executable, [string[]]$PrefixArguments) {
    try {
        $probe = & $Executable @PrefixArguments -c "import struct,sys; print(f'{sys.version_info.major}.{sys.version_info.minor}|{struct.calcsize(chr(80))*8}')" 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $probe) {
            return $null
        }
        $fields = @($probe)[-1].Trim().Split('|')
        if ($fields.Count -ne 2) { return $null }
        $parts = $fields[0].Split('.')
        if (
            [int]$parts[0] -eq 3 -and
            [int]$parts[1] -ge 11 -and
            [int]$parts[1] -le 13 -and
            [int]$fields[1] -eq 64
        ) {
            return [PSCustomObject]@{
                Executable = $Executable
                PrefixArguments = $PrefixArguments
                Version = $fields[0]
                Architecture = '64-bit'
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
        throw 'FGO_LOCAL_PYTHON does not point to a supported 64-bit Python 3.11-3.13 executable.'
    }

    if (Test-Path -LiteralPath $managedPython -PathType Leaf) {
        $candidate = Test-PythonCandidate $managedPython @()
        if ($candidate) { return $candidate }
    }

    if (Test-Path -LiteralPath $legacyManagedPython -PathType Leaf) {
        $candidate = Test-PythonCandidate $legacyManagedPython @()
        if ($candidate) { return $candidate }
    }

    foreach ($version in @('3.13', '3.12', '3.11')) {
        foreach ($hive in @('HKCU:', 'HKLM:')) {
            $registryPath = "$hive\Software\Python\PythonCore\$version\InstallPath"
            if (-not (Test-Path -LiteralPath $registryPath)) { continue }
            try {
                $installRoot = (Get-Item -LiteralPath $registryPath).GetValue('')
                if (-not [string]::IsNullOrWhiteSpace([string]$installRoot)) {
                    $candidate = Test-PythonCandidate (Join-Path ([string]$installRoot) 'python.exe') @()
                    if ($candidate) { return $candidate }
                }
            } catch {
                # Continue to the remaining registered and PATH candidates.
            }
        }
    }

    $pyLauncher = Get-Command 'py.exe' -ErrorAction SilentlyContinue
    if ($pyLauncher) {
        foreach ($selector in @('-3.13-64', '-3.12-64', '-3.11-64', '-3.13', '-3.12', '-3.11')) {
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

function Move-Aside([string]$Path, [string]$Label) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $expectedPrefix = $root.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($expectedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to move $Label outside the project directory."
    }
    $suffix = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = "$resolved.incompatible-$suffix"
    if (Test-Path -LiteralPath $backup) {
        $backup = "$backup-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
    }
    Move-Item -LiteralPath $resolved -Destination $backup
    Write-Step "Preserved the incompatible $Label as a backup."
}

function Invoke-Download([string]$Url, [string]$Destination) {
    $uri = [Uri]$Url
    if ($uri.Scheme -ne 'https' -or $uri.Host -ne 'www.python.org') {
        throw 'Refusing an unexpected Python download source.'
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    $partial = "$Destination.part"
    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source --location --fail --retry 3 --retry-delay 2 --connect-timeout 20 --speed-limit 1024 --speed-time 60 --continue-at - --output $partial $Url
        if ($LASTEXITCODE -ne 0) {
            throw 'Python download failed. Check the Internet connection and run the launcher again.'
        }
    } else {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial
    }
    Move-Item -LiteralPath $partial -Destination $Destination -Force
}

function Install-ManagedPython {
    if (-not (Confirm-AutomaticSetup 'No compatible 64-bit Python was found. Download and install a private Python 3.13.15 runtime from python.org?')) {
        throw 'Python setup was declined. Install 64-bit Python 3.11-3.13, or set FGO_LOCAL_PYTHON to python.exe.'
    }

    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    if (Test-Path -LiteralPath $pythonInstaller -PathType Leaf) {
        $cachedSignature = Get-AuthenticodeSignature -LiteralPath $pythonInstaller
        $cachedSigner = if ($cachedSignature.SignerCertificate) { $cachedSignature.SignerCertificate.Subject } else { '' }
        $cachedHash = (Get-FileHash -LiteralPath $pythonInstaller -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($cachedHash -ne $pythonInstallerSha256 -or $cachedSignature.Status -ne 'Valid' -or $cachedSigner -notmatch 'Python Software Foundation') {
            $invalid = "$pythonInstaller.invalid-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
            Move-Item -LiteralPath $pythonInstaller -Destination $invalid
            Write-Warning 'A cached Python installer failed signature validation and was preserved without being executed.'
        }
    }
    if (-not (Test-Path -LiteralPath $pythonInstaller -PathType Leaf)) {
        Write-Step 'Downloading the official Python 3.13.15 64-bit installer...'
        Invoke-Download $pythonInstallerUrl $pythonInstaller
    }

    $signature = Get-AuthenticodeSignature -LiteralPath $pythonInstaller
    $signer = if ($signature.SignerCertificate) { $signature.SignerCertificate.Subject } else { '' }
    $installerHash = (Get-FileHash -LiteralPath $pythonInstaller -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($installerHash -ne $pythonInstallerSha256 -or $signature.Status -ne 'Valid' -or $signer -notmatch 'Python Software Foundation') {
        $invalid = "$pythonInstaller.invalid-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
        Move-Item -LiteralPath $pythonInstaller -Destination $invalid
        throw 'The downloaded Python installer did not have a valid Python Software Foundation signature. It was not executed.'
    }

    Write-Step 'Installing Python into user_data (system PATH and file associations are not modified)...'
    New-Item -ItemType Directory -Path $managedPythonRoot -Force | Out-Null
    $arguments = @(
        '/quiet',
        'InstallAllUsers=0',
        "TargetDir=`"$managedPythonRoot`"",
        'Include_pip=1',
        'Include_launcher=0',
        'Include_doc=0',
        'Include_test=0',
        'Include_tcltk=0',
        'AssociateFiles=0',
        'Shortcuts=0',
        'PrependPath=0',
        'AppendPath=0'
    )
    $process = Start-Process -FilePath $pythonInstaller -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "The private Python installer exited with code $($process.ExitCode)."
    }
    $candidate = Test-PythonCandidate $managedPython @()
    if (-not $candidate -or $candidate.Version -ne '3.13') {
        throw 'Private Python installation completed, but the runtime validation failed.'
    }
    return $candidate
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
if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'FgoGotran Local requires 64-bit Windows.'
}
if (-not (Test-Path -LiteralPath (Join-Path $root 'pyproject.toml') -PathType Leaf)) {
    throw "pyproject.toml was not found in $root"
}
if ($data.TrimEnd('\') -eq $root.TrimEnd('\')) {
    throw 'The user data directory must not be the project root.'
}

New-Item -ItemType Directory -Path $data -Force | Out-Null

if (Test-Path -LiteralPath $venvPython -PathType Leaf) {
    $existingEnvironment = Test-PythonCandidate $venvPython @()
    if (-not $existingEnvironment) {
        Move-Aside $venv 'Python environment'
    }
} elseif (Test-Path -LiteralPath $venv) {
    Move-Aside $venv 'incomplete Python environment'
}

if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
    $python = Find-CompatiblePython
    if (-not $python) {
        $python = Install-ManagedPython
    }
    Write-Step "Creating the private Python environment with Python $($python.Version) $($python.Architecture)..."
    & $python.Executable @($python.PrefixArguments) -m venv $venv
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to create .venv.'
    }
}

$venvValidation = Test-PythonCandidate $venvPython @()
if (-not $venvValidation) {
    throw 'The private Python environment is not a supported 64-bit Python 3.11-3.13 environment.'
}

$sourceRoot = Join-Path $root 'src'
$sitePackagesOutput = & $venvPython -c "import pathlib,site; print(next(p for p in site.getsitepackages() if pathlib.Path(p).name.lower() == 'site-packages'))"
if ($LASTEXITCODE -ne 0 -or -not $sitePackagesOutput) {
    throw 'The private Python site-packages directory could not be located.'
}
$sitePackages = [System.IO.Path]::GetFullPath(@($sitePackagesOutput)[-1].Trim())
$venvPrefix = [System.IO.Path]::GetFullPath($venv).TrimEnd('\') + '\'
if (-not $sitePackages.StartsWith($venvPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'The private Python site-packages directory resolved outside .venv.'
}
Write-Utf8NoBom (Join-Path $sitePackages 'fgogotran-local-src.pth') $sourceRoot

$dependencyFiles = @(
    (Join-Path $root 'requirements.txt'),
    (Join-Path $root 'pyproject.toml')
)
$dependencyFingerprint = "v2:$(Get-DependencyFingerprint $dependencyFiles)"
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
    & $venvPython -m pip install --disable-pip-version-check --requirement (Join-Path $root 'requirements.txt')
    if ($LASTEXITCODE -ne 0) {
        throw 'Dependency installation failed. Check the Internet connection and run Start-FgoGotranLocal.cmd again.'
    }
    Write-Utf8NoBom $marker $dependencyFingerprint
}

Write-Step 'Python environment is ready.'
Write-Output $venvPython
