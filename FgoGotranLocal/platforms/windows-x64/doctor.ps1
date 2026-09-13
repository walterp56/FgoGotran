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
Write-Host "Project: $root"
Write-Host "User data: $data"

$pythonExists = Test-Path -LiteralPath $python -PathType Leaf
if ($pythonExists) {
    $pythonDetail = & $python -c "import platform,sys; print(f'{sys.version.split()[0]} ({platform.architecture()[0]})')" 2>$null | Select-Object -Last 1
    Report $(if ($LASTEXITCODE -eq 0 -and $pythonDetail) { 'OK' } else { 'FAIL' }) 'Python environment' $(if ($pythonDetail) { $pythonDetail.Trim() } else { 'The private runtime could not be inspected.' })
    & $python -c "import fastapi, fgogotran_local, gradio, uvicorn" 2>$null
    Report $(if ($LASTEXITCODE -eq 0) { 'OK' } else { 'FAIL' }) 'Python dependencies' $(if ($LASTEXITCODE -eq 0) { 'Ready' } else { 'Required packages could not be imported.' })
} else {
    Report 'FAIL' 'Python environment' 'The private environment was not created.'
}

$llamaPath = ''
$modelPath = ''
$managedBackend = ''
$managedRelease = ''
$configPath = Join-Path $data 'config.json'
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    try {
        $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
        Report 'OK' 'Configuration' $configPath
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
        $managedBackend = [string]$manifest.backend
        $managedRelease = [string]$manifest.llamaRelease
        $managedDetail = @($managedRelease, $managedBackend) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        Report 'OK' 'Managed runtime' $(if ($managedDetail) { $managedDetail -join ' / ' } else { $manifestPath })
    } catch {
        Report 'WARN' 'Managed runtime' 'The managed runtime manifest could not be read.'
    }
} else {
    Report 'WARN' 'Managed runtime' 'No auto-managed runtime is installed; configured external files can still be used.'
}

$llamaReady = -not [string]::IsNullOrWhiteSpace($llamaPath) -and (Test-Path -LiteralPath $llamaPath -PathType Leaf)
Report $(if ($llamaReady) { 'OK' } else { 'WARN' }) 'llama-server.exe' $(if ($llamaReady) { $llamaPath } else { 'Run automatic setup again or configure it in the browser.' })
$modelReady = -not [string]::IsNullOrWhiteSpace($modelPath) -and (Test-Path -LiteralPath $modelPath -PathType Leaf)
Report $(if ($modelReady) { 'OK' } else { 'WARN' }) 'Active GGUF model' $(if ($modelReady) { $modelPath } else { 'Download a compatible GGUF yourself, then select it in the browser.' })

$gpuTool = Get-Command 'nvidia-smi.exe' -ErrorAction SilentlyContinue
if ($gpuTool) {
    $gpuName = & $gpuTool.Source --query-gpu=name --format=csv,noheader 2>$null | Select-Object -First 1
    Report $(if ($gpuName) { 'OK' } else { 'WARN' }) 'NVIDIA GPU' $(if ($gpuName) { $gpuName.Trim() } else { 'nvidia-smi returned no GPU.' })
} else {
    Report 'WARN' 'NVIDIA GPU' 'nvidia-smi was not found; the CPU llama.cpp build can still work.'
}
