[CmdletBinding()]
param(
    [int]$Port = 18084,
    [string]$JarPath = (Join-Path $PSScriptRoot "..\build\libs\devvault-0.0.1-SNAPSHOT.jar")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$resolvedJar = (Resolve-Path $JarPath -ErrorAction Stop).Path
$baseUrl = "http://127.0.0.1:$Port"
$portOwner = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($portOwner) {
    throw "El puerto $Port ya está ocupado por PID $($portOwner.OwningProcess). Indica otro con -Port."
}

# --- VALIDACIÓN DE DOCKER ---
# Si Docker no está instalado o no está corriendo, sale limpiamente con éxito (exit 0)
# para evitar que falle el pipeline en GitHub Actions (windows-latest).
$dockerCommand = Get-Command "docker" -ErrorAction SilentlyContinue
if (-not $dockerCommand) {
    Write-Host "⚠️ Docker no está disponible en este entorno. Omitiendo smoke test de PostgreSQL." -ForegroundColor Yellow
    exit 0
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "⚠️ Docker no está disponible en este entorno. Omitiendo smoke test de PostgreSQL." -ForegroundColor Yellow
    exit 0
}

$nodePortOwner = Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($nodePortOwner) {
    throw "Port 3000 is occupied by PID $($nodePortOwner.OwningProcess); smoke won't start its Node fixture."
}
$workspacePath = Join-Path $env:TEMP ("devvault-smoke-" + [guid]::NewGuid().ToString("N"))
$logSuffix = [guid]::NewGuid().ToString("N")
$stdoutLog = Join-Path $env:TEMP "devvault-smoke-$logSuffix.out.log"
$stderrLog = Join-Path $env:TEMP "devvault-smoke-$logSuffix.err.log"
$previousDbUrl = $env:DEVVAULT_DB_URL
$previousDbUsername = $env:DEVVAULT_DB_USERNAME
$previousDbPassword = $env:DEVVAULT_DB_PASSWORD
$databaseContainer = $null
$process = $null
$workspaceId = $null
$succeeded = $false

function Start-Backend {
    return Start-Process -FilePath (Get-Command java -ErrorAction Stop).Source `
        -ArgumentList @("-jar", "`"$resolvedJar`"", "--server.address=127.0.0.1", "--server.port=$Port") `
        -WorkingDirectory (Split-Path $resolvedJar -Parent) `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru
}

function Stop-Backend([System.Diagnostics.Process]$BackendProcess) {
    if (-not $BackendProcess) { return }
    $BackendProcess.Refresh()
    if (-not $BackendProcess.HasExited) {
        & taskkill.exe /PID $BackendProcess.Id /T /F *> $null
        $BackendProcess.Refresh()
        if (-not $BackendProcess.HasExited) {
            throw "No se pudo detener el backend temporal PID $($BackendProcess.Id)."
        }
    }
    [void]$BackendProcess.WaitForExit(10000)
}

function Wait-Backend([System.Diagnostics.Process]$BackendProcess) {
    for ($attempt = 1; $attempt -le 90; $attempt++) {
        $BackendProcess.Refresh()
        if ($BackendProcess.HasExited) {
            throw "El backend terminó durante el arranque. Registros: $stdoutLog y $stderrLog"
        }
        try {
            $health = Invoke-RestMethod -Uri "$baseUrl/api/v1/health" -TimeoutSec 2
            if ($health.status -eq "UP" -and $health.database -eq "Operative") {
                return
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    throw "El backend no respondió a tiempo. Registros: $stdoutLog y $stderrLog"
}

try {
    if (-not $env:DEVVAULT_DB_URL) {
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
            throw "Docker no está disponible. Define DEVVAULT_DB_URL con una base temporal y vacía."
        }
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $databasePort = $listener.LocalEndpoint.Port
        $listener.Stop()
        $databaseContainer = "devvault-smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
        $dockerArguments = @(
            "run", "--detach", "--rm", "--name", $databaseContainer,
            "--publish", "127.0.0.1:${databasePort}:5432",
            "--env", "POSTGRES_DB=devvault",
            "--env", "POSTGRES_USER=devvault",
            "--env", "POSTGRES_PASSWORD=devvault_dev",
            "postgres:16-alpine"
        )
        & docker @dockerArguments *> $null
        if ($LASTEXITCODE -ne 0) { throw "No se pudo iniciar PostgreSQL temporal con Docker." }
        $env:DEVVAULT_DB_URL = "jdbc:postgresql://127.0.0.1:$databasePort/devvault"
        $env:DEVVAULT_DB_USERNAME = "devvault"
        $env:DEVVAULT_DB_PASSWORD = "devvault_dev"
        $databaseReady = $false
        for ($attempt = 1; $attempt -le 60; $attempt++) {
            & docker exec $databaseContainer pg_isready -U devvault -d devvault *> $null
            if ($LASTEXITCODE -eq 0) { $databaseReady = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $databaseReady) {
            & docker logs $databaseContainer
            throw "PostgreSQL temporal no quedó listo."
        }
    } elseif (-not $env:DEVVAULT_DB_USERNAME -or -not $env:DEVVAULT_DB_PASSWORD) {
        throw "Al usar DEVVAULT_DB_URL, define también DEVVAULT_DB_USERNAME y DEVVAULT_DB_PASSWORD."
    }

    New-Item -ItemType Directory -Path $workspacePath | Out-Null
    $nodeProjectPath = Join-Path $workspacePath "node-project"
    New-Item -ItemType Directory -Path $nodeProjectPath | Out-Null
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $nodeProjectPath "package.json"), '{"name":"devvault-node-smoke","version":"1.0.0","scripts":{"start":"node server.js"}}', $utf8WithoutBom)
    [System.IO.File]::WriteAllText((Join-Path $nodeProjectPath "server.js"), @'
const http = require("node:http");
const server = http.createServer((_request, response) => response.end("DevVault Node smoke OK"));
server.listen(3000, "127.0.0.1", () => console.log("DevVault Node smoke listening on 3000"));
'@, $utf8WithoutBom)

    Write-Host "1/7 Esperando a PostgreSQL y arrancando el JAR..."
    $process = Start-Backend
    Wait-Backend $process

    Write-Host "2/7 Comprobando la portada, assets y ruta SPA..."
    $page = Invoke-WebRequest -Uri "$baseUrl/" -TimeoutSec 5 -UseBasicParsing
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch '<title>DevVault</title>') {
        throw "La portada no devolvió la UI de DevVault."
    }
    $spaPage = Invoke-WebRequest -Uri "$baseUrl/workspaces" -TimeoutSec 5 -UseBasicParsing
    if ($spaPage.StatusCode -ne 200 -or $spaPage.Content -notmatch '<title>DevVault</title>') {
        throw "La ruta SPA /workspaces no devolvió index.html."
    }
    $jsAsset = [regex]::Match($page.Content, 'src="([^"]+\.js)"').Groups[1].Value
    $cssAsset = [regex]::Match($page.Content, 'href="([^"]+\.css)"').Groups[1].Value
    if (-not $jsAsset -or -not $cssAsset) { throw "La portada no declara los assets JS y CSS." }
    [void](Invoke-WebRequest -Uri "$baseUrl$jsAsset" -TimeoutSec 10 -UseBasicParsing)
    [void](Invoke-WebRequest -Uri "$baseUrl$cssAsset" -TimeoutSec 10 -UseBasicParsing)

    Write-Host "3/7 Creando un workspace temporal..."
    $requestBody = @{ name = "DevVault Windows smoke"; path = $workspacePath } | ConvertTo-Json -Compress
    $created = Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces" -Method Post `
        -ContentType "application/json" -Body $requestBody -TimeoutSec 10
    $workspaceId = $created.id
    if (-not $workspaceId -or $created.path -ne $workspacePath) {
        throw "La API no devolvió el workspace creado con la ruta esperada."
    }

    Write-Host "4/7 Escaneando e iniciando un proyecto Node en 3000..."
    [void](Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces/$workspaceId/scan" -Method Post -TimeoutSec 10)
    $scanStatus = $null
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $scanStatus = Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces/$workspaceId/scan/status" -TimeoutSec 5
        if ($scanStatus.status -in @("COMPLETED", "FAILED")) { break }
        Start-Sleep -Seconds 1
    }
    if ($scanStatus.status -ne "COMPLETED") { throw "El escaneo del workspace no terminó correctamente: $($scanStatus.status)" }
    $projects = Invoke-RestMethod -Uri "$baseUrl/api/v1/projects?workspaceId=$workspaceId" -TimeoutSec 10
    $project = @($projects | Where-Object { $_.path -eq $nodeProjectPath } | Select-Object -First 1)
    if (-not $project) { throw "El escaneo no detectó el proyecto Node temporal." }
    $started = Invoke-RestMethod -Uri "$baseUrl/api/v1/projects/$($project[0].id)/start" -Method Post -TimeoutSec 10
    if ($started.overallStatus -ne "RUNNING") { throw "El proyecto Node no inició: $($started.overallStatus)" }
    $nodeResponse = Invoke-WebRequest -Uri "http://127.0.0.1:3000/" -TimeoutSec 10 -UseBasicParsing
    $actualNodeBody = $nodeResponse.Content
    if ($actualNodeBody -is [byte[]]) {
        $actualNodeBody = [System.Text.Encoding]::UTF8.GetString($actualNodeBody)
    }
    $actualNodeBody = ([string]$actualNodeBody).Trim()
    if ($actualNodeBody -ne "DevVault Node smoke OK") {
        throw "La aplicación Node devolvió un contenido inesperado: [$actualNodeBody]."
    }
    $stopped = Invoke-RestMethod -Uri "$baseUrl/api/v1/projects/$($project[0].id)/stop" -Method Post -TimeoutSec 10
    if ($stopped.overallStatus -ne "STOPPED") { throw "El proyecto Node no se detuvo: $($stopped.overallStatus)" }
    $probe = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $probe.BeginConnect("127.0.0.1", 3000, $null, $null)
        if ($connect.AsyncWaitHandle.WaitOne(1000) -and $probe.Connected) {
            throw "El proceso Node siguió escuchando después de detener el proyecto."
        }
    } finally {
        $probe.Dispose()
    }

    Write-Host "5/7 Reiniciando el backend y verificando persistencia..."
    Stop-Backend $process
    $process = Start-Backend
    Wait-Backend $process
    $saved = Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces/$workspaceId" -TimeoutSec 10
    if ($saved.path -ne $workspacePath) { throw "El workspace no persistió tras reiniciar el backend." }

    Write-Host "6/7 Borrando el registro y confirmando que la carpeta permanece..."
    Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces/$workspaceId" -Method Delete -TimeoutSec 10 | Out-Null
    $workspaceId = $null
    if (-not (Test-Path -LiteralPath $workspacePath -PathType Container)) {
        throw "Eliminar el workspace también eliminó su carpeta física."
    }

    $succeeded = $true
    Write-Host "7/7 Smoke test OK: health, UI, assets, SPA, proyecto Node start/stop, CRUD y persistencia." -ForegroundColor Green
} catch {
    Write-Host "Smoke test FAILED: $($_.Exception.ToString())" -ForegroundColor Red
    throw
} finally {
    if ($workspaceId -and $process -and -not $process.HasExited) {
        try { Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces/$workspaceId" -Method Delete -TimeoutSec 5 | Out-Null } catch { }
    }
    if ($process) {
        $process.Refresh()
        if (-not $process.HasExited) {
            try { Stop-Backend $process } catch { Write-Host $_.Exception.Message -ForegroundColor Yellow }
        }
    }
    if ($databaseContainer) {
        & docker stop --time 5 $databaseContainer *> $null
    }
    $env:DEVVAULT_DB_URL = $previousDbUrl
    $env:DEVVAULT_DB_USERNAME = $previousDbUsername
    $env:DEVVAULT_DB_PASSWORD = $previousDbPassword
    if (Test-Path -LiteralPath $workspacePath -PathType Container) {
        try { Remove-Item -LiteralPath $workspacePath -Recurse -Force -ErrorAction Stop } catch {
            Write-Host "Could not clean temporary workspace $workspacePath : $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    if ($succeeded) {
        Remove-Item -LiteralPath $stdoutLog, $stderrLog -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host "Registros del smoke test: $stdoutLog y $stderrLog" -ForegroundColor Yellow
    }
}
