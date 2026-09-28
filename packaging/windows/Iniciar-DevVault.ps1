$ErrorActionPreference = "Stop"

$packageRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$composeFile = Join-Path $packageRoot "compose.preview.yml"
$jarFile = Join-Path $packageRoot "devvault.jar"
$javaFile = Join-Path $packageRoot "runtime\bin\java.exe"
$dataRoot = Join-Path $env:LOCALAPPDATA "DevVault\preview"
$pidFile = Join-Path $dataRoot "backend.pid"
$stdoutLog = Join-Path $dataRoot "backend.out.log"
$stderrLog = Join-Path $dataRoot "backend.err.log"
# La API y la UI se sirven en el mismo origen, así que no hay CORS de por medio.
# El health check va contra 127.0.0.1 porque es la dirección a la que el backend
# enlaza; el navegador se abre con localhost, que es lo que pide la guía.
$apiBaseUrl = "http://127.0.0.1:5050"
$appUrl = "http://localhost:5050/login"
$appPort = 5050

function Stop-WithMessage([string]$message) {
    Write-Host "`n$message" -ForegroundColor Red
    Read-Host "Presiona Enter para cerrar"
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Stop-WithMessage "No se encontró Docker. Instala Docker Desktop y vuelve a abrir DevVault."
}

try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker no responde" }
} catch {
    Stop-WithMessage "Docker Desktop no está iniciado. Ábrelo, espera a que indique que está listo y vuelve a intentar."
}

try {
    docker compose -p devvault-preview -f $composeFile up -d postgres
    if ($LASTEXITCODE -ne 0) { throw "No se pudo iniciar PostgreSQL" }
} catch {
    Stop-WithMessage "No se pudo iniciar la base de datos. Revisa que el puerto local 5434 esté disponible."
}

$databaseReady = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    docker compose -p devvault-preview -f $composeFile exec -T postgres pg_isready -U devvault -d devvault *> $null
    if ($LASTEXITCODE -eq 0) { $databaseReady = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $databaseReady) { Stop-WithMessage "PostgreSQL no quedó listo. Consulta Docker Desktop e inténtalo de nuevo." }

try {
    $existingResponse = Invoke-WebRequest -Uri "$apiBaseUrl/api/v1/health" -TimeoutSec 2 -UseBasicParsing
    if ($existingResponse.StatusCode -eq 200) {
        Start-Process $appUrl
        Write-Host "DevVault ya estaba iniciado. Se abrió en el navegador."
        exit 0
    }
} catch {
    # No hay una instancia saludable; se intenta iniciar la incluida en el paquete.
}

if (-not (Test-Path $javaFile) -or -not (Test-Path $jarFile)) {
    Stop-WithMessage "El paquete está incompleto: faltan el runtime de Java o el backend. Vuelve a extraer DevVault."
}

New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
$portOwner = Get-NetTCPConnection -LocalPort $appPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($portOwner) {
    Stop-WithMessage "El puerto $appPort ya está ocupado por otro proceso (PID $($portOwner.OwningProcess)). Cierra ese proceso y vuelve a iniciar DevVault."
}

$process = Start-Process -FilePath $javaFile `
    -ArgumentList "-jar `"$jarFile`" --spring.profiles.active=preview" `
    -WorkingDirectory $packageRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru
Set-Content -Path $pidFile -Value $process.Id -Encoding ascii

$backendReady = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    if ($process.HasExited) { break }
    try {
        $response = Invoke-WebRequest -Uri "$apiBaseUrl/api/v1/health" -TimeoutSec 2 -UseBasicParsing
        if ($response.StatusCode -eq 200) { $backendReady = $true; break }
    } catch {
        Start-Sleep -Seconds 2
    }
}

if (-not $backendReady) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    Stop-WithMessage "DevVault no pudo iniciar. Los registros están en $dataRoot."
}

Start-Process $appUrl
Write-Host "DevVault está listo en $appUrl"
