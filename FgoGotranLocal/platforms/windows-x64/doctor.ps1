param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot,

    [Parameter(Mandatory = $true)]
    [string]$DataDirectory
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$root = (Get-Item -LiteralPath $ProjectRoot -Force).FullName
$data = [System.IO.Path]::GetFullPath($DataDirectory)
$python = Join-Path $root '.venv\Scripts\python.exe'
$manifestPaths = @(
    (Join-Path $data 'runtime\windows-x64\managed-runtime.json'),
    (Join-Path $data 'runtime\managed-runtime.json')
)
$manifestPath = $manifestPaths | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1

function Report([string]$State, [string]$Label, [string]$Detail) {
    $color = if ($State -eq 'OK') { 'Green' } elseif ($State -eq 'WARN') { 'Yellow' } else { 'Red' }
    Write-Host "[$State] $Label - $Detail" -ForegroundColor $color
}

Write-Host 'FgoGotran Local environment check'

$pythonExists = Test-Path -LiteralPath $python -PathType Leaf
if ($pythonExists) {
    & $python -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)" 2>$null
    Report $(if ($LASTEXITCODE -eq 0) { 'OK' } else { 'FAIL' }) 'Python environment' $(if ($LASTEXITCODE -eq 0) { 'Ready' } else { 'The private runtime is not compatible.' })
    & $python -c "import fastapi, fgogotran_local, gradio, uvicorn" 2>$null
    Report $(if ($LASTEXITCODE -eq 0) { 'OK' } else { 'FAIL' }) 'Python dependencies' $(if ($LASTEXITCODE -eq 0) { 'Ready' } else { 'Required packages could not be imported.' })
} else {
    Report 'FAIL' 'Python environment' 'The private environment was not created.'
}

$llamaPath = ''
$modelPath = ''
$configPath = Join-Path $data 'config.json'
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    try {
        $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
        Report 'OK' 'Configuration' 'Ready'
        $llamaPath = [string]$config.llamaServerPath
        $activeId = [string]$config.activeProfile
        $profileProperty = if ($config.profiles -and $activeId) { $config.profiles.PSObject.Properties[$activeId] } else { $null }
        if ($profileProperty) { $modelPath = [string]$profileProperty.Value.modelPath }
    } catch {
        Report 'FAIL' 'Configuration' 'config.json is not valid UTF-8 JSON.'
    }
} else {
    Report 'WARN' 'Configuration' 'It will be created when the control interface starts.'
}

if ($manifestPath -and (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    try {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ([string]::IsNullOrWhiteSpace($llamaPath)) { $llamaPath = [string]$manifest.llamaServerPath }
        Report 'OK' 'Managed runtime' 'Ready'
    } catch {
        Report 'WARN' 'Managed runtime' 'The managed runtime manifest could not be read.'
    }
} else {
    Report 'WARN' 'Managed runtime' 'No auto-managed runtime is installed; configured external files can still be used.'
}

$llamaReady = -not [string]::IsNullOrWhiteSpace($llamaPath) -and (Test-Path -LiteralPath $llamaPath -PathType Leaf)
Report $(if ($llamaReady) { 'OK' } else { 'WARN' }) 'llama-server.exe' $(if ($llamaReady) { 'Configured and available' } else { 'Run automatic setup again or configure it in the browser.' })
$modelReady = -not [string]::IsNullOrWhiteSpace($modelPath) -and (Test-Path -LiteralPath $modelPath -PathType Leaf)
Report $(if ($modelReady) { 'OK' } else { 'WARN' }) 'Active GGUF model' $(if ($modelReady) { 'Configured and available' } else { 'Download a compatible GGUF yourself, then select it in the browser.' })
