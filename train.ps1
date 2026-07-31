param(
    [Parameter(Position = 0)]
    [string]$Command = "status",

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Output = Join-Path $Root "training\output"
$Venv = Join-Path $Root ".training-env"
$VenvPython = Join-Path $Venv "Scripts\python.exe"
$ControlScript = Join-Path $Root "training\control.py"
$SupervisorScript = Join-Path $Root "training\supervisor.py"
$PipelineScript = Join-Path $Root "training\pipeline.py"
$StartupEntry = Join-Path ([Environment]::GetFolderPath("Startup")) "hnefatafl-training.cmd"

function Find-JavaBin {
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javac) {
        return Split-Path -Parent $javac.Source
    }

    $candidateRoots = @(
        (Join-Path $Root ".training-tools"),
        (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium"),
        (Join-Path $env:LOCALAPPDATA "Programs\Adoptium"),
        (Join-Path $env:ProgramFiles "Eclipse Adoptium")
    )
    foreach ($candidateRoot in $candidateRoots) {
        if (-not (Test-Path $candidateRoot)) {
            continue
        }
        $match = Get-ChildItem $candidateRoot -Recurse -Filter "javac.exe" -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($match) {
            return $match.Directory.FullName
        }
    }
    return $null
}

function Enable-JavaPath {
    $javaBin = Find-JavaBin
    if (-not $javaBin) {
        return $false
    }
    if (-not (($env:Path -split ";") -contains $javaBin)) {
        $env:Path = "$javaBin;$env:Path"
    }
    return $true
}

function Ensure-WinGet {
    if (Get-Command winget.exe -ErrorAction SilentlyContinue) {
        return
    }
    try {
        Add-AppxPackage -RegisterByFamilyName -MainPackage Microsoft.DesktopAppInstaller_8wekyb3d8bbwe
    }
    catch {
        throw "WinGet is unavailable. Install Microsoft App Installer, then rerun train.cmd start."
    }
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        throw "WinGet is unavailable. Install Microsoft App Installer, then rerun train.cmd start."
    }
}

function Ensure-Prerequisites {
    Ensure-WinGet

    $pythonLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
    if (-not $pythonLauncher) {
        Write-Host "Installing Python 3.12 for the current user..."
        winget install --id Python.Python.3.12 --exact --scope user `
            --accept-package-agreements --accept-source-agreements --disable-interactivity
        $pythonLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
    }
    if (-not $pythonLauncher) {
        $possibleLauncher = Join-Path $env:LOCALAPPDATA "Programs\Python\Launcher\py.exe"
        if (Test-Path $possibleLauncher) {
            $pythonLauncher = Get-Item $possibleLauncher
        }
    }
    if (-not $pythonLauncher) {
        throw "Python 3.12 installation completed but py.exe could not be found. Sign out once and rerun."
    }

    if (-not (Enable-JavaPath)) {
        Write-Host "Installing Temurin JDK 21..."
        winget install --id EclipseAdoptium.Temurin.21.JDK --exact --scope user `
            --accept-package-agreements --accept-source-agreements --disable-interactivity
        if (-not (Enable-JavaPath)) {
            Write-Host "The JDK installer has no per-user build; downloading a portable JDK instead..."
            $tools = Join-Path $Root ".training-tools"
            $archive = Join-Path $tools "temurin-21.zip"
            $expanded = Join-Path $tools "temurin-21"
            New-Item -ItemType Directory -Path $tools -Force | Out-Null
            Invoke-WebRequest `
                -Uri "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" `
                -OutFile $archive
            if (Test-Path $expanded) {
                Remove-Item $expanded -Recurse -Force
            }
            Expand-Archive -Path $archive -DestinationPath $expanded -Force
            Remove-Item $archive -Force
            if (-not (Enable-JavaPath)) {
                throw "The portable JDK download completed but javac.exe could not be found."
            }
        }
    }

    if (-not (Test-Path $VenvPython)) {
        Write-Host "Creating the isolated Python environment..."
        $launcherPath = if ($pythonLauncher.Source) {
            $pythonLauncher.Source
        }
        else {
            $pythonLauncher.FullName
        }
        & $launcherPath -3.12 -m venv $Venv
    }
    if (-not (Test-Path $VenvPython)) {
        throw "Could not create $Venv."
    }

    & $VenvPython -m pip install --upgrade pip
    & $VenvPython -m pip install -r (Join-Path $Root "training\requirements-lock.txt")
    $torchReady = $false
    try {
        & $VenvPython -c "import torch; assert torch.__version__.startswith('2.12.'); print(torch.__version__)"
        $torchReady = ($LASTEXITCODE -eq 0)
    }
    catch {
        $torchReady = $false
    }
    if (-not $torchReady) {
        Write-Host "Installing the CUDA 12.6 PyTorch build (this is the largest download)..."
        & $VenvPython -m pip install "torch==2.12.0" `
            --index-url "https://download.pytorch.org/whl/cu126"
    }

    if (-not (Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue)) {
        throw "nvidia-smi was not found. Install/update the NVIDIA driver, reboot, then rerun train.cmd start."
    }
    & $VenvPython -c "import torch; assert torch.cuda.is_available(), 'CUDA unavailable'; print('GPU:', torch.cuda.get_device_name(0))"
    if ($LASTEXITCODE -ne 0) {
        throw "PyTorch cannot use the NVIDIA GPU. Update the driver and rerun train.cmd start."
    }
}

function Get-TrainingState {
    $statePath = Join-Path $Output "state.json"
    if (-not (Test-Path $statePath)) {
        return $null
    }
    try {
        return Get-Content $statePath -Raw | ConvertFrom-Json
    }
    catch {
        return $null
    }
}

function Test-SupervisorRunning {
    $pidPath = Join-Path $Output "supervisor.pid"
    if (-not (Test-Path $pidPath)) {
        return $false
    }
    $supervisorPid = Get-Content $pidPath -ErrorAction SilentlyContinue
    if (-not $supervisorPid) {
        return $false
    }
    return $null -ne (Get-Process -Id ([int]$supervisorPid) -ErrorAction SilentlyContinue)
}

function Start-Supervisor([double]$Days, [switch]$Smoke) {
    if (Test-SupervisorRunning) {
        Write-Host "The training supervisor is already running."
        return
    }
    if (-not (Test-Path $VenvPython)) {
        throw "The training environment is missing. Run train.cmd start first."
    }
    Enable-JavaPath | Out-Null
    $arguments = @(
        "`"$SupervisorScript`"",
        "--days",
        $Days.ToString([Globalization.CultureInfo]::InvariantCulture)
    )
    if ($Smoke) {
        $arguments += "--smoke"
    }
    $process = Start-Process -FilePath $VenvPython -ArgumentList $arguments `
        -WorkingDirectory $Root -WindowStyle Hidden -PassThru
    Write-Host "Training supervisor started (PID $($process.Id))."
}

function Add-StartupEntry {
    $content = "@echo off`r`ncall `"$Root\train.cmd`" autoresume`r`n"
    Set-Content -Path $StartupEntry -Value $content -Encoding ASCII
}

function Remove-StartupEntry {
    Remove-Item $StartupEntry -Force -ErrorAction SilentlyContinue
}

function Get-DaysArgument {
    $days = 3.0
    for ($index = 0; $index -lt $Rest.Count; $index++) {
        if ($Rest[$index] -eq "--days" -and $index + 1 -lt $Rest.Count) {
            $days = [double]::Parse($Rest[$index + 1], [Globalization.CultureInfo]::InvariantCulture)
            $index++
        }
        else {
            throw "Unknown start argument: $($Rest[$index])"
        }
    }
    if ($days -le 0) {
        throw "--days must be positive."
    }
    return $days
}

Set-Location $Root

switch ($Command.ToLowerInvariant()) {
    "start" {
        $days = Get-DaysArgument
        Ensure-Prerequisites
        & $VenvPython $PipelineScript doctor
        if ($LASTEXITCODE -ne 0) {
            throw "Training diagnostics failed."
        }
        & $VenvPython $ControlScript initialize
        Add-StartupEntry
        Start-Supervisor -Days $days
    }
    "autoresume" {
        $state = Get-TrainingState
        if (-not $state -or $state.status -in @("complete", "stopped", "failed")) {
            exit 0
        }
        if (-not (Test-Path $VenvPython)) {
            exit 0
        }
        Enable-JavaPath | Out-Null
        Start-Supervisor -Days 3
    }
    "resume" {
        if (-not (Test-Path $VenvPython)) {
            throw "Run train.cmd start once before resume."
        }
        $state = Get-TrainingState
        if ($state -and $state.status -in @("complete", "stopped", "failed")) {
            throw "This run is $($state.status). Use train.cmd start --days 3 to begin a new run."
        }
        & $VenvPython $ControlScript desired running
        Start-Supervisor -Days 3
    }
    "pause" {
        & $VenvPython $ControlScript desired paused
    }
    "stop" {
        & $VenvPython $ControlScript desired stopped
        Remove-StartupEntry
        Write-Host "Stop requested; checkpoints and replay data were preserved."
    }
    "mode" {
        if ($Rest.Count -ne 1 -or $Rest[0] -notin @("study", "balanced", "max", "auto")) {
            throw "Usage: train.cmd mode study|balanced|max|auto"
        }
        & $VenvPython $ControlScript mode $Rest[0]
    }
    "extend" {
        if ($Rest.Count -ne 1) {
            throw "Usage: train.cmd extend 6h"
        }
        & $VenvPython $ControlScript extend $Rest[0]
    }
    "finalize" {
        & $VenvPython $ControlScript finalize
    }
    "status" {
        if (Test-Path $VenvPython) {
            & $VenvPython $ControlScript status
        }
        else {
            py -3.12 $ControlScript status
        }
    }
    "logs" {
        $pipelineLog = Join-Path $Output "logs\pipeline.log"
        $supervisorLog = Join-Path $Output "logs\supervisor.log"
        if (Test-Path $pipelineLog) {
            Write-Host "=== pipeline.log ==="
            Get-Content $pipelineLog -Tail 100
        }
        if (Test-Path $supervisorLog) {
            Write-Host "=== supervisor.log ==="
            Get-Content $supervisorLog -Tail 50
        }
    }
    "doctor" {
        Ensure-Prerequisites
        & $VenvPython $PipelineScript doctor
    }
    "smoke" {
        Ensure-Prerequisites
        & $VenvPython $ControlScript initialize
        Enable-JavaPath | Out-Null
        & $VenvPython $SupervisorScript --days 0.01 --smoke
    }
    default {
        Write-Host "Usage:"
        Write-Host "  train.cmd start --days 3"
        Write-Host "  train.cmd status|pause|resume|finalize|stop|logs|doctor|smoke"
        Write-Host "  train.cmd mode study|balanced|max|auto"
        Write-Host "  train.cmd extend 6h"
        exit 2
    }
}
