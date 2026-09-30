<#
.SYNOPSIS
    Detiene el DevVault arrancado con Iniciar-dev.ps1.

.DESCRIPTION
    Cierra la JVM del backend comprobando antes que el PID guardado siga siendo
    el proceso de DevVault. No se limita a matar por PID: si el sistema operativo
    ya reutilizo ese identificador para otro programa, el script no lo toca.

    Por defecto deja PostgreSQL corriendo, porque es lo que hace que el siguiente
    arranque sea mas rapido. Usa -StopDatabase si prefieres detenerlo tambien.

.PARAMETER StopDatabase
    Detiene tambien el contenedor de PostgreSQL.

.EXAMPLE
    .\scripts\Detener-dev.ps1

.EXAMPLE
    .\scripts\Detener-dev.ps1 -StopDatabase
#>
[CmdletBinding()]
param(
    [switch]$StopDatabase
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$dataRoot = Join-Path $env:LOCALAPPDATA "DevVault\dev"
$pidFile = Join-Path $dataRoot "backend.pid"

function Invoke-NativeCommand {
    <#
    Docker escribe parte de su salida normal en stderr, y con
    ErrorActionPreference = Stop PowerShell lo convierte en un error fatal aunque
    el comando haya funcionado. Se relaja la preferencia solo durante la llamada
    y se devuelve el codigo de salida, que es lo que indica si fallo de verdad.
    #>
    param(
        [Parameter(Mandatory)] [string] $FilePath,
        [string[]] $Arguments = @()
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $FilePath @Arguments *> $null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

if (-not (Test-Path $pidFile)) {
    Write-Host "No hay ningun DevVault en ejecucion desde este repositorio."
    exit 0
}

$backendPid = 0
[void][int]::TryParse((Get-Content $pidFile -Raw).Trim(), [ref]$backendPid)

if ($backendPid -gt 0) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $backendPid" -ErrorAction SilentlyContinue

    if (-not $processInfo) {
        Write-Host "El proceso ya no existe. Se limpia el archivo de PID."
    }
    elseif ($processInfo.CommandLine -notlike "*devvault.jar*") {
        # El PID fue reutilizado por otro programa. Destrozarlo seria un error.
        Write-Host "El PID $backendPid ya no corresponde a DevVault, asi que no se toca." -ForegroundColor Yellow
        Write-Host "  Proceso actual: $($processInfo.Name)" -ForegroundColor Yellow
    }
    else {
        Stop-Process -Id $backendPid -Force -ErrorAction SilentlyContinue
        for ($attempt = 1; $attempt -le 15; $attempt++) {
            if (-not (Get-Process -Id $backendPid -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Milliseconds 200
        }
        if (Get-Process -Id $backendPid -ErrorAction SilentlyContinue) {
            Write-Host "El backend no se detuvo y quedo el proceso $backendPid. Puede estar bloqueado." -ForegroundColor Yellow
        } else {
            Write-Host "Backend detenido."
        }
    }
}

Remove-Item $pidFile -Force -ErrorAction SilentlyContinue

if ($StopDatabase) {
    $compose = Join-Path $backendRoot "docker-compose.yml"
    if ((Invoke-NativeCommand -FilePath "docker" -Arguments @("compose", "-f", $compose, "stop", "postgres")) -eq 0) {
        Write-Host "PostgreSQL detenido. Los datos siguen en el volumen devvault_pgdata."
    } else {
        Write-Host "No se pudo detener PostgreSQL. Si Docker Desktop esta cerrado, puedes ignorarlo."
    }
}
else {
    Write-Host "PostgreSQL sigue corriendo. Usa -StopDatabase para detenerlo tambien."
}
