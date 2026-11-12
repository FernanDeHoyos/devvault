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
# Comprobamos si el comando docker existe Y si el motor de Docker está corriendo activamente
$dockerAvailable = $false
try {
    if (Get-Command "docker" -ErrorAction SilentlyContinue) {
        # 'docker info' devuelve código 0 solo si el demonio está activo
        docker info *> $null
        if ($LASTEXITCODE -eq 0) {
            $dockerAvailable = $true
        }
    }
} catch {
    $dockerAvailable = $false
}

# Docker solo hace falta para levantar el PostgreSQL temporal. Si el workflow
# ya aprovisionó una base y la pasa por DEVVAULT_DB_URL, exigir Docker aquí
# haría que el smoke test se omitiera justo en el caso en que sí puede correr.
if (-not $dockerAvailable -and -not $env:DEVVAULT_DB_URL) {
    Write-Host "⚠️ Docker no está disponible o el servicio no está activo en este entorno. Omitiendo smoke test de PostgreSQL." -ForegroundColor Yellow
    exit 0
}
# ----------------------------

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
        Stop-Process -Id $BackendProcess.Id -Force
        if (-not $BackendProcess.WaitForExit(10000)) {
            throw "El backend temporal PID $($BackendProcess.Id) no terminó a tiempo."
        }
        $BackendProcess.Refresh()
        if (-not $BackendProcess.HasExited) {
            throw "No se pudo detener el backend temporal PID $($BackendProcess.Id)."
        }
    }
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
    $dockerOutput = & docker @dockerArguments 2>&1
if ($LASTEXITCODE -ne 0) { throw "No se pudo iniciar PostgreSQL temporal con Docker: $dockerOutput" }
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
    # El backend guarda la ruta resuelta con Path.toRealPath(), que en Windows
    # expande los nombres 8.3. En el runner TEMP es C:\Users\RUNNER~1\... y la
    # API responde C:\Users\runneradmin\..., así que sin normalizar aquí las
    # comparaciones exactas de ruta fallan por la forma y no por un defecto.
    $workspacePath = (Get-Item -LiteralPath $workspacePath).FullName
    $nodeProjectPath = Join-Path $workspacePath "node-project"
    New-Item -ItemType Directory -Path $nodeProjectPath | Out-Null
    $infrastructurePath = Join-Path $nodeProjectPath "infrastructure"
    New-Item -ItemType Directory -Path $infrastructurePath | Out-Null
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $nodeProjectPath "package.json"), '{"name":"devvault-node-smoke","version":"1.0.0","scripts":{"start":"node server.js"},"dependencies":{"left-pad":"^1.3.0"}}', $utf8WithoutBom)
    [System.IO.File]::WriteAllText((Join-Path $infrastructurePath "compose.yml"), @'
services:
  db:
    image: postgres:16
'@, $utf8WithoutBom)
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
    $javascript = Invoke-WebRequest -Uri "$baseUrl$jsAsset" -TimeoutSec 10 -UseBasicParsing
    if ($javascript.Content -notmatch "Arquitectura del proyecto") {
        throw "El bundle servido no contiene el panel de inteligencia del proyecto. Reconstruye el JAR con la UI actualizada."
    }
    $hasArchitectureInventory = $javascript.Content -match "Dependencias declaradas" `
        -and $javascript.Content -match "Bases de datos" `
        -and $javascript.Content -match "Servicios declarados"
    if (-not $hasArchitectureInventory) {
        throw "El bundle servido no contiene el inventario arquitectónico actualizado."
    }
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
    $intelligence = Invoke-RestMethod -Uri "$baseUrl/api/v1/projects/$($project[0].id)/intelligence" -TimeoutSec 10
    $detectedJavaScript = @($intelligence.stack | Where-Object { $_.name -eq "JavaScript" })
    $detectedNode = @($intelligence.stack | Where-Object { $_.name -eq "Node.js" })
    if (-not $detectedJavaScript -or -not $detectedNode) {
        throw "El endpoint de inteligencia no devolvió el stack JavaScript/Node del proyecto de prueba."
    }
    if (-not $detectedNode[0].evidence -or $intelligence.map.nodes.Count -lt 3 -or $intelligence.map.edges.Count -lt 2) {
        throw "El endpoint de inteligencia no devolvió evidencias y relaciones para el mapa del proyecto."
    }
    if (-not (@($intelligence.dependencies | Where-Object { $_.name -eq "left-pad" -and $_.scope -eq "PRODUCTION" }))) {
        throw "El endpoint de inteligencia no devolvió la dependencia declarada del proyecto."
    }
    if (-not (@($intelligence.databases | Where-Object { $_.name -eq "PostgreSQL" -and $_.status -eq "DECLARED" }))) {
        throw "El endpoint de inteligencia no reconoció la base de datos declarada en Compose."
    }
    if (-not (@($intelligence.services | Where-Object { $_.name -eq "db" }))) {
        throw "El endpoint de inteligencia no devolvió el servicio Compose de prueba."
    }
    # The API waits for readiness (up to 300 s); the HTTP client must not cancel it after 10 s.
    $started = Invoke-RestMethod -Uri "$baseUrl/api/v1/projects/$($project[0].id)/start" -Method Post -TimeoutSec 330
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

    Write-Host "Comprobando el entorno guardado con un proceso Node real..."
    $environmentBody = @{
        name = "Smoke environment"
        projects = @(@{ projectId = $project[0].id; dependsOn = @() })
    } | ConvertTo-Json -Depth 6 -Compress
    $environmentBase = "$baseUrl/api/v1/workspaces/$workspaceId/environments"
    $environment = Invoke-RestMethod -Uri $environmentBase -Method Post -ContentType "application/json" -Body $environmentBody -TimeoutSec 10
    foreach ($operation in @("START", "STOP")) {
        $operationBody = @{ operation = $operation } | ConvertTo-Json -Compress
        $run = Invoke-RestMethod -Uri "$environmentBase/$($environment.id)/runs" -Method Post -ContentType "application/json" -Body $operationBody -TimeoutSec 10
        for ($attempt = 0; $attempt -lt 660; $attempt++) {
            Start-Sleep -Milliseconds 500
            $history = Invoke-RestMethod -Uri "$environmentBase/$($environment.id)/runs" -TimeoutSec 10
            $finishedRun = $history | Where-Object { $_.id -eq $run.id } | Select-Object -First 1
            if ($finishedRun -and $finishedRun.status -ne "RUNNING") { break }
        }
        if (-not $finishedRun -or $finishedRun.status -ne "COMPLETED") {
            $details = $finishedRun | ConvertTo-Json -Depth 6 -Compress
            throw "La operación $operation del entorno no terminó correctamente: $details"
        }
        if ($operation -eq "START") {
            [void](Invoke-WebRequest -Uri "http://127.0.0.1:3000/" -TimeoutSec 5 -UseBasicParsing)
        }
    }

    Write-Host "5/7 Reiniciando el backend y verificando persistencia..."
    Stop-Backend $process
    $process = Start-Backend
    Wait-Backend $process
    $saved = Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces/$workspaceId" -TimeoutSec 10
    if ($saved.path -ne $workspacePath) { throw "El workspace no persistió tras reiniciar el backend." }
    $savedEnvironments = Invoke-RestMethod -Uri $environmentBase -TimeoutSec 10
    if (-not ($savedEnvironments | Where-Object { $_.id -eq $environment.id })) { throw "El entorno no persistió tras reiniciar." }
    $savedRuns = Invoke-RestMethod -Uri "$environmentBase/$($environment.id)/runs" -TimeoutSec 10
    if ($savedRuns.Count -ne 2) { throw "El historial del entorno no persistió tras reiniciar." }

    Write-Host "6/7 Borrando el registro y confirmando que la carpeta permanece..."
    Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces/$workspaceId" -Method Delete -TimeoutSec 10 | Out-Null
    $workspaceId = $null
    if (-not (Test-Path -LiteralPath $workspacePath -PathType Container)) {
        throw "Eliminar el workspace también eliminó su carpeta física."
    }

    $succeeded = $true
    Write-Host "7/7 Smoke test OK: health, UI de inteligencia, stack/mapa e inventario, assets, SPA, Node start/stop, CRUD y persistencia." -ForegroundColor Green
} catch {
    Write-Host "Smoke test FAILED: $($_.Exception.ToString())" -ForegroundColor Red
    throw
} finally {
    if ($workspaceId -and $process -and -not $process.HasExited) {
        try { Invoke-RestMethod -Uri "$baseUrl/api/v1/workspaces/$workspaceId" -Method Delete -TimeoutSec 5 | Out-Null } catch { }
    }
    if ($process) {
        $process.Refresh()
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)" -ErrorAction SilentlyContinue
        $ownsSmokeBackend = -not $process.HasExited -and [bool]$processInfo `
            -and $processInfo.CommandLine -like "*$resolvedJar*--server.port=$Port*"
        if ($ownsSmokeBackend) {
            try {
                Stop-Process -Id $process.Id -Force
                if (-not $process.WaitForExit(10000)) {
                    Write-Host "El backend temporal PID $($process.Id) no terminó a tiempo." -ForegroundColor Yellow
                }
            } catch {
                Write-Host $_.Exception.Message -ForegroundColor Yellow
            }
        }
    }
    $portListener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($portListener) {
        $listenerInfo = Get-CimInstance Win32_Process `
            -Filter "ProcessId = $($portListener.OwningProcess)" -ErrorAction SilentlyContinue
        $isSmokeBackend = $listenerInfo -and $listenerInfo.CommandLine -like "*$resolvedJar*--server.port=$Port*"
        if ($isSmokeBackend) {
            Stop-Process -Id $portListener.OwningProcess -Force
        }
    }
    if ($databaseContainer) {
        & docker stop --time 5 $databaseContainer *> $null
    }
    $env:DEVVAULT_DB_URL = $previousDbUrl
    $env:DEVVAULT_DB_USERNAME = $previousDbUsername
    $env:DEVVAULT_DB_PASSWORD = $previousDbPassword
    if (Test-Path -LiteralPath $workspacePath -PathType Container) {
        $resolvedCleanup = [IO.Path]::GetFullPath($workspacePath)
        $resolvedTempRoot = (Get-Item -LiteralPath $env:TEMP).FullName.TrimEnd('\') + '\'
        if (-not $resolvedCleanup.StartsWith($resolvedTempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not ([IO.Path]::GetFileName($resolvedCleanup).StartsWith('devvault-smoke-'))) {
            throw "Se rechazó la limpieza de una ruta ajena al workspace temporal del smoke test."
        }
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
