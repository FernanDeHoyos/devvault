<#
.SYNOPSIS
    Arranca DevVault en modo desarrollo con un solo comando.

.DESCRIPTION
    Deja DevVault funcionando de la forma mas corta posible desde un clon
    recien bajado:

        1. Comprueba que el JDK 17 y Docker estén disponibles.
        2. Levanta PostgreSQL con Docker Compose y espera a que acepte
           conexiones.
        3. Compila el backend incluyendo la UI ya compilada del repositorio
           hermano, de modo que el frontend se sirva desde el mismo origen.
        4. Arranca el backend, espera a que responda el health check y abre el
           navegador.

    Al servir la UI desde el propio backend no hace falta instalar Node, ni
    ejecutar npm install, ni levantar un segundo servidor de Vite. Tampoco hay
    CORS de por medio porque todo vive en el mismo origen.

    La UI llega como submodulo en la carpeta ui/, y su repositorio versiona la
    carpeta dist ya compilada, asi que un clon recien bajado trae el frontend
    listo para servirse.

    El estado (PID, logs) queda en %LOCALAPPDATA%\DevVault\dev, separado del de
    la preview empaquetada, que ademas usa otro puerto de PostgreSQL.

.PARAMETER Port
    Puerto del backend. Por defecto 8080.

.PARAMETER UiDistPath
    Carpeta con la UI ya compilada. Por defecto la carpeta dist del repositorio
    hermano devvault-ui.

.PARAMETER NoBrowser
    No abre el navegador al terminar.

.PARAMETER Reuse
    Reutiliza el JAR ya compilado si es mas reciente que los fuentes. Acelera
    arranques posteriores, pero no recompila si editaste codigo Java.

.EXAMPLE
    .\scripts\Iniciar-dev.ps1

.EXAMPLE
    .\scripts\Iniciar-dev.ps1 -Port 8090 -NoBrowser
#>
[CmdletBinding()]
param(
    [int]$Port = 8080,
    [string]$UiDistPath = (Join-Path $PSScriptRoot "..\ui\dist"),
    [switch]$NoBrowser,
    [switch]$Reuse
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$dataRoot = Join-Path $env:LOCALAPPDATA "DevVault\dev"
$pidFile = Join-Path $dataRoot "backend.pid"
$stdoutLog = Join-Path $dataRoot "backend.out.log"
$stderrLog = Join-Path $dataRoot "backend.err.log"
$jarFile = Join-Path $backendRoot "build\libs\devvault.jar"
$startedAt = Get-Date

function Step([string]$message) {
    $elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
    Write-Host ("  [{0,3}s] {1}" -f $elapsed, $message)
}

function Fail([string]$message) {
    Write-Host ""
    Write-Host "No se pudo iniciar DevVault." -ForegroundColor Red
    Write-Host $message -ForegroundColor Red
    exit 1
}

function Invoke-NativeCommand {
    <#
    Ejecuta un comando nativo y devuelve su codigo de salida.

    Docker y Java escriben parte de su salida normal en stderr, y con
    ErrorActionPreference = Stop PowerShell convierte eso en un error fatal que
    aborta el script aunque el comando haya ido bien. Este envoltorio relaja esa
    preferencia solo durante la llamada y devuelve el codigo, que es lo que de
    verdad indica si algo fallo.

    Con -Quiet descarta la salida; si el comando falla, se imprime para que el
    mensaje de error tenga contexto.
    #>
    param(
        [Parameter(Mandatory)] [string] $FilePath,
        [string[]] $Arguments = @(),
        [switch] $Quiet
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $FilePath @Arguments 2>&1
        $code = $LASTEXITCODE
        if (-not $Quiet -or $code -ne 0) {
            $output | Out-String | Write-Host
        }
        return $code
    } finally {
        $ErrorActionPreference = $previous
    }
}

# --- 1. Java ---------------------------------------------------------------

Write-Host ""
Write-Host "DevVault - arranque en modo desarrollo" -ForegroundColor Cyan
Write-Host ""

$javaExe = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $javaExe = $onPath.Source }
}

if (-not $javaExe) {
    Fail "No se encontro Java. Instala un JDK 17 (https://adoptium.net/temurin/releases/?version=17) y define JAVA_HOME."
}

# `java -version` escribe en stderr, que con ErrorActionPreference = Stop seria un
# error fatal. Se relaja la preferencia solo durante la consulta.
$strictErrorAction = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javaVersion = (& $javaExe -version 2>&1 | Select-Object -First 1)
$ErrorActionPreference = $strictErrorAction

if ($javaVersion -notmatch '"(\d+)\.') {
    Fail "No se pudo leer la version de Java desde $javaExe"
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 17) {
    Fail "DevVault necesita Java 17 o superior y $javaExe es Java $javaMajor. Spring Boot 3.4 no arranca en versiones anteriores."
}
Step "Java $javaMajor en $javaExe"

# --- 2. Docker -------------------------------------------------------------

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail "No se encontro Docker. DevVault usa Docker para PostgreSQL y para administrar contenedores de proyectos."
}
if ((Invoke-NativeCommand -FilePath "docker" -Arguments @("info") -Quiet) -ne 0) {
    Fail "Docker no responde. Abre Docker Desktop, espera a que indique que esta listo y vuelve a intentarlo."
}
Step "Docker responde"

# --- 3. PostgreSQL ---------------------------------------------------------

$compose = Join-Path $backendRoot "docker-compose.yml"
if ((Invoke-NativeCommand -FilePath "docker" -Arguments @("compose", "-f", $compose, "up", "-d", "postgres") -Quiet) -ne 0) {
    Fail "No se pudo arrancar PostgreSQL con Docker Compose. Revisa que el puerto 5433 este libre."
}

$databaseReady = $false
for ($attempt = 1; $attempt -le 30; $attempt++) {
    if ((Invoke-NativeCommand -FilePath "docker" -Arguments @("exec", "devvault-postgres", "pg_isready", "-U", "devvault", "-d", "devvault") -Quiet) -eq 0) {
        $databaseReady = $true
        break
    }
    Start-Sleep -Seconds 1
}
if (-not $databaseReady) {
    Fail "PostgreSQL no quedo listo despues de 30 segundos. Consulta 'docker logs devvault-postgres'."
}
Step "PostgreSQL acepta conexiones en el puerto 5433"

# --- 4. UI compilada -------------------------------------------------------
#
# El repositorio de la UI versiona su carpeta dist, asi que un clon recien bajado
# ya trae el frontend compilado y no hace falta Node para arrancar. Si no esta,
# el backend funciona igual pero sin interfaz, y avisamos.

$embedUi = $false
$uiRoot = (Resolve-Path (Join-Path $PSScriptRoot "..") | Join-Path -ChildPath "ui")
if (Test-Path (Join-Path $UiDistPath "index.html")) {
    $embedUi = $true
    Step "UI encontrada en $UiDistPath"
} else {
    Write-Host "  No se encontro la UI compilada en $UiDistPath." -ForegroundColor Yellow
    # Un clon sin --recurse-submodules deja la carpeta ui/ creada pero vacia, asi
    # que probar si existe no sirve: hay que mirar si el submodulo esta
    # inicializado de verdad, y eso se reconoce por su archivo .git.
    if (-not (Test-Path (Join-Path $uiRoot ".git"))) {
        Write-Host "  El submodulo de la UI no esta inicializado. Ejecuta:" -ForegroundColor Yellow
        Write-Host "    git submodule update --init --recursive" -ForegroundColor Yellow
    } else {
        Write-Host "  DevVault arrancara solo con la API. Para tener interfaz, compila la UI con:" -ForegroundColor Yellow
        Write-Host "    cd ui; npm install; npm run build" -ForegroundColor Yellow
    }
}

# --- 5. Compilar -----------------------------------------------------------

$gradle = Join-Path $backendRoot "gradlew.bat"
if (-not (Test-Path $gradle)) { $gradle = Join-Path $backendRoot "gradlew" }

# Que el JAR este al dia depende de dos cosas: los fuentes del backend y, si se
# embebe, la UI compilada. Comparar solo contra src/main hacia que un JAR
# construido sin UI se reutilizara despues de inicializar el submodulo, y la
# aplicacion arrancaria sin interfaz sin avisar.
$needsBuild = $true
if ($Reuse -and (Test-Path $jarFile)) {
    $jarTime = (Get-Item $jarFile).LastWriteTime
    $inputs = @(Get-ChildItem (Join-Path $backendRoot "src\main") -Recurse -File -ErrorAction SilentlyContinue)
    if ($embedUi) {
        $inputs += @(Get-ChildItem $UiDistPath -Recurse -File -ErrorAction SilentlyContinue)
    }
    $newestInput = $inputs | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newestInput -and $newestInput.LastWriteTime -lt $jarTime) {
        $needsBuild = $false
    }
}

if ($needsBuild) {
    if ($embedUi) {
        $gradleArgs = @("-p", $backendRoot, "bootJar", "-PpreviewUiDir=$((Resolve-Path $UiDistPath).Path)", "-q", "--console=plain")
    } else {
        $gradleArgs = @("-p", $backendRoot, "bootJar", "-q", "--console=plain")
    }
    if ((Invoke-NativeCommand -FilePath $gradle -Arguments $gradleArgs -Quiet) -ne 0) {
        Fail "La compilacion del backend fallo. Ejecuta '.\gradlew.bat build' para ver el detalle."
    }

    # El nombre del JAR incluye la version de build.gradle, asi que se localiza
    # en vez de escribirlo a mano. Se excluye el destino: la copia de la
    # ejecucion anterior tambien esta en esa carpeta y seria mas reciente que el
    # JAR recien compilado, con lo que el script intentaria copiarse a si mismo.
    # El sufijo -plain.jar es la version sin dependencias y no sirve para ejecutar.
    $builtJar = Get-ChildItem (Join-Path $backendRoot "build\libs") -Filter "*.jar" |
        Where-Object { $_.Name -notlike "*-plain.jar" -and $_.FullName -ne $jarFile } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $builtJar) {
        Fail "Gradle no genero ningun JAR ejecutable en build\libs."
    }
    Copy-Item $builtJar.FullName $jarFile -Force
    Step "Backend compilado"
} else {
    Step "Se reutiliza el JAR ya compilado"
}

if (-not (Test-Path $jarFile)) {
    Fail "No se encontro el JAR compilado en $jarFile"
}

# --- 6. Arrancar -----------------------------------------------------------

New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null

$portOwner = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($portOwner) {
    Fail "El puerto $Port ya lo ocupa el proceso $($portOwner.OwningProcess). Cierralo o ejecuta con -Port distinto."
}

# El PID que se guarda es el de la JVM, no el de Gradle, para que Detener-dev
# pueda cerrarla de forma fiable.
$process = Start-Process -FilePath $javaExe `
    -ArgumentList "-jar", "`"$jarFile`"", "--server.port=$Port" `
    -WorkingDirectory $backendRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru
Set-Content -Path $pidFile -Value $process.Id -Encoding ascii

$backendReady = $false
for ($attempt = 1; $attempt -le 60; $attempt++) {
    if ($process.HasExited) {
        Fail "El backend termino de inmediato. Registros en $stderrLog"
    }
    try {
        $response = Invoke-RestMethod "http://127.0.0.1:$Port/api/v1/health" -TimeoutSec 2
        if ($response.status -eq "UP") { $backendReady = $true; break }
    } catch {
        Start-Sleep -Seconds 1
    }
}
if (-not $backendReady) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    Fail "El backend no respondio a tiempo. Registros en $stdoutLog y $stderrLog"
}
Step "Backend sano en http://127.0.0.1:$Port"

# --- 7. Resumen -----------------------------------------------------------

Write-Host ""
Write-Host "  DevVault listo" -ForegroundColor Green
if ($embedUi) {
    Write-Host "  Interfaz     http://localhost:$Port/login"
} else {
    Write-Host "  Interfaz     no disponible (falta la UI compilada)"
}
Write-Host "  API          http://127.0.0.1:$Port/api/v1"
Write-Host "  Registros    $stdoutLog"
Write-Host "  Detener      .\scripts\Detener-dev.ps1"
Write-Host ""

# Abrir el navegador solo tiene sentido si hay interfaz que abrir: sin UI esa
# ruta devuelve 404 y el usuario ve un error en vez de un mensaje util.
if ($embedUi -and -not $NoBrowser) {
    Start-Process "http://localhost:$Port/login"
}
