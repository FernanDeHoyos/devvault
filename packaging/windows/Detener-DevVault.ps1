$ErrorActionPreference = "Continue"

$packageRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$composeFile = Join-Path $packageRoot "compose.preview.yml"
$dataRoot = Join-Path $env:LOCALAPPDATA "DevVault\preview"
$pidFile = Join-Path $dataRoot "backend.pid"
$javaFile = Join-Path $packageRoot "runtime\bin\java.exe"

if (Test-Path $pidFile) {
    $backendPid = 0
    [void][int]::TryParse((Get-Content $pidFile -Raw).Trim(), [ref]$backendPid)
    if ($backendPid -gt 0) {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $backendPid" -ErrorAction SilentlyContinue
        if ($processInfo -and $processInfo.ExecutablePath -eq $javaFile -and $processInfo.CommandLine -like "*devvault.jar*") {
            Stop-Process -Id $backendPid -Force -ErrorAction SilentlyContinue
            Write-Host "Backend detenido."
        }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

docker compose -p devvault-preview -f $composeFile stop postgres
if ($LASTEXITCODE -eq 0) {
    Write-Host "PostgreSQL detenido. Los datos permanecen guardados para el próximo inicio."
} else {
    Write-Host "No se pudo detener PostgreSQL. Si Docker Desktop está cerrado, puedes ignorar este mensaje."
}
