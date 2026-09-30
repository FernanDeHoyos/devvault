param(
    [string]$UiPath = (Join-Path $PSScriptRoot "..\..\ui"),
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Version = "0.4.0-preview.1",
    [switch]$SkipUiBuild
)

$ErrorActionPreference = "Stop"

if ($Version -notmatch '^[A-Za-z0-9.-]+$') {
    throw "La versión solo puede contener letras, números, punto y guion."
}

$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$uiBuildDir = Join-Path $backendRoot "build\preview-ui"

if ($SkipUiBuild) {
    # Reutiliza lo que ya había en build/preview-ui. Sirve para los cambios que
    # solo tocan el backend o los scripts, sin exigir Node en la máquina.
    if (-not (Test-Path (Join-Path $uiBuildDir "index.html"))) {
        throw "No hay UI compilada en $uiBuildDir. Ejecuta el empaquetado sin -SkipUiBuild para generarla."
    }
} else {
    # Si el clon se hizo sin --recurse-submodules, la carpeta ui/ existe pero
    # esta vacia. Sin este aviso el error siguiente seria "no encuentro Vite",
    # que no apunta a la causa real.
    if (-not (Test-Path $UiPath)) {
        throw "La UI no esta disponible en $UiPath. Inicializa el submodulo con: git submodule update --init --recursive"
    }
    $uiRoot = (Resolve-Path $UiPath -ErrorAction Stop).Path
    $uiBuilder = Join-Path $uiRoot "node_modules\.bin\vite.cmd"
    if (-not (Test-Path $uiBuilder)) {
        throw "No encuentro Vite en $uiRoot. Ejecuta 'npm ci' dentro de la carpeta ui antes de empaquetar."
    }
}

if (-not $JdkHome) {
    $jlinkCommand = Get-Command jlink.exe -ErrorAction SilentlyContinue
    if (-not $jlinkCommand) { throw "Se necesita un JDK 17 para crear el runtime incluido. Instálalo en la máquina que genera la preview." }
    $JdkHome = Split-Path (Split-Path $jlinkCommand.Source -Parent) -Parent
}
$JdkHome = (Resolve-Path $JdkHome -ErrorAction Stop).Path
$jlink = Join-Path $JdkHome "bin\jlink.exe"
if (-not (Test-Path $jlink)) { throw "No encuentro jlink.exe bajo $JdkHome. Indica la carpeta raíz de un JDK 17." }
$jdkVersion = (& $jlink --version | Select-Object -First 1).Trim()
if ($jdkVersion -notmatch '^17\.') {
    throw "El empaquetado está fijado a Java 17 para mantener un runtime reproducible. JDK detectado: $jdkVersion"
}

$distributionRoot = Join-Path $backendRoot "build\distribution"
$stageRoot = Join-Path $distributionRoot "DevVault-$Version-windows-x64"
$archive = Join-Path $distributionRoot "DevVault-$Version-windows-x64.zip"
$gradle = Join-Path $backendRoot "gradlew.bat"

New-Item -ItemType Directory -Force -Path $distributionRoot | Out-Null
if (Test-Path $stageRoot) {
    $resolvedStage = (Resolve-Path $stageRoot).Path
    if (-not $resolvedStage.StartsWith($distributionRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "La carpeta de salida quedó fuera de build/distribution; se cancela para proteger otros archivos."
    }
    Remove-Item -LiteralPath $resolvedStage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stageRoot | Out-Null

if ($SkipUiBuild) {
    Write-Host "1/4 Reutilizando la UI ya compilada en build\preview-ui..."
} else {
    Write-Host "1/4 Compilando UI..."
    Push-Location $uiRoot
    try {
        & $uiBuilder build --outDir $uiBuildDir --emptyOutDir
        if ($LASTEXITCODE -ne 0) { throw "Falló la compilación de la UI." }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path (Join-Path $uiBuildDir "index.html"))) { throw "La compilación de la UI no generó index.html." }
}

Write-Host "2/4 Generando el backend con la UI integrada..."
Push-Location $backendRoot
try {
    & $gradle bootJar "-PpreviewUiDir=$uiBuildDir"
    if ($LASTEXITCODE -ne 0) { throw "Falló la generación del JAR." }
} finally {
    Pop-Location
}

$jarFile = Get-ChildItem (Join-Path $backendRoot "build\libs") -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*-plain.jar" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jarFile) { throw "Gradle no generó un JAR ejecutable." }

Write-Host "3/4 Creando el runtime reducido de Java 17..."
$runtimeDir = Join-Path $stageRoot "runtime"
& $jlink --add-modules ALL-MODULE-PATH --strip-debug --no-man-pages --no-header-files --compress=2 --output $runtimeDir
if ($LASTEXITCODE -ne 0) { throw "No se pudo crear el runtime de Java incluido." }

Copy-Item $jarFile.FullName (Join-Path $stageRoot "devvault.jar")
Copy-Item (Join-Path $PSScriptRoot "compose.preview.yml") $stageRoot
Copy-Item (Join-Path $PSScriptRoot "Iniciar-DevVault.ps1") $stageRoot
Copy-Item (Join-Path $PSScriptRoot "Detener-DevVault.ps1") $stageRoot
Copy-Item (Join-Path $PSScriptRoot "Iniciar-DevVault.bat") $stageRoot
Copy-Item (Join-Path $PSScriptRoot "Detener-DevVault.bat") $stageRoot
Copy-Item (Join-Path $PSScriptRoot "LEEME.txt") $stageRoot

Write-Host "4/4 Comprimiendo el paquete..."
if (Test-Path $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -Path (Join-Path $stageRoot "*") -DestinationPath $archive -CompressionLevel Optimal
$archiveSize = [math]::Round((Get-Item $archive).Length / 1MB, 1)
Write-Host "Preview lista: $archive ($archiveSize MB)"
