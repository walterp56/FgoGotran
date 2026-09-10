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

function Report([string]$State, [string]$Label, [string]$Detail) {
    $color = if ($State -eq 'OK') { 'Green' } elseif ($State -eq 'WARN') { 'Yellow' } else { 'Red' }
    Write-Host "[$State] $Label - $Detail" -ForegroundColor $color
}

Write-Host 'FgoGotran Local environment check'
Write-Host "Project: $root"
Write-Host "User data: $data"

$pythonExists = Test-Path -LiteralPath $python -PathType Leaf
Report $(if ($pythonExists) { 'OK' } else { 'FAIL' }) 'Python environment' $(if ($pythonExists) { $python } else { 'The private environment was not created.' })
if ($pythonExists) {
    & $python -c "import fastapi, fgogotran_local, gradio, uvicorn" 2>$null
    Report $(if ($LASTEXITCODE -eq 0) { 'OK' } else { 'FAIL' }) 'Python dependencies' $(if ($LASTEXITCODE -eq 0) { 'Ready' } else { 'Required packages could not be imported.' })
}

$configPath = Join-Path $data 'config.json'
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    try {
        $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
        Report 'OK' 'Configuration' $configPath
        $llamaReady = -not [string]::IsNullOrWhiteSpace($config.llamaServerPath) -and (Test-Path -LiteralPath $config.llamaServerPath -PathType Leaf)
        Report $(if ($llamaReady) { 'OK' } else { 'WARN' }) 'llama-server.exe' $(if ($llamaReady) { $config.llamaServerPath } else { 'Configure it in the browser.' })
        $profile = $config.profiles.($config.activeProfile)
        $modelReady = $profile -and -not [string]::IsNullOrWhiteSpace($profile.modelPath) -and (Test-Path -LiteralPath $profile.modelPath -PathType Leaf)
        Report $(if ($modelReady) { 'OK' } else { 'WARN' }) 'Active GGUF model' $(if ($modelReady) { $profile.modelPath } else { 'Configure it in the browser.' })
    } catch {
        Report 'FAIL' 'Configuration' 'config.json is not valid UTF-8 JSON.'
    }
} else {
    Report 'WARN' 'Configuration' 'It will be created when the browser interface starts.'
}

$gpuTool = Get-Command 'nvidia-smi.exe' -ErrorAction SilentlyContinue
if ($gpuTool) {
    $gpuName = & $gpuTool.Source --query-gpu=name --format=csv,noheader 2>$null | Select-Object -First 1
    Report $(if ($gpuName) { 'OK' } else { 'WARN' }) 'NVIDIA GPU' $(if ($gpuName) { $gpuName.Trim() } else { 'nvidia-smi returned no GPU.' })
} else {
    Report 'WARN' 'NVIDIA GPU' 'nvidia-smi was not found; a CPU or another llama.cpp backend may still work.'
}
