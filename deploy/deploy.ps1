# YouSong presentation deployment (Windows, Docker Desktop).
# Usage: powershell -ExecutionPolicy Bypass -File .\deploy.ps1 [up|demo|ip|status|logs [service]|stop|reset]
param(
    [Parameter(Position = 0)][string]$Command = "up",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest
)
$ErrorActionPreference = "Stop"

$DeployDir = $PSScriptRoot
$Config = Join-Path $DeployDir "config.env"
$GitHub = "https://github.com/Proyecto-Nuclear"

function Say($Message) { Write-Host ""; Write-Host "==> $Message" -ForegroundColor Cyan }
function Die($Message) { Write-Host ""; Write-Host "ERROR: $Message" -ForegroundColor Red; exit 1 }

function Get-Cfg($Key) {
    if (-not (Test-Path $Config)) { return "" }
    $value = ""
    foreach ($line in Get-Content $Config) {
        if ($line -match "^$Key=(.*)$") { $value = $Matches[1].Trim().Trim('"') }
    }
    return $value
}

function Set-Cfg($Key, $Value) {
    $lines = @(Get-Content $Config)
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^$Key=") { $lines[$i] = "$Key=$Value"; $found = $true }
    }
    if (-not $found) { $lines += "$Key=$Value" }
    # UTF-8 without BOM, LF line endings: docker compose reads it as an env file.
    [System.IO.File]::WriteAllText($Config, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
}

function New-RandomHex($Bytes) {
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return (($buffer | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Test-PrivateIp($Ip) {
    if ($Ip -notmatch '^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$') { return $false }
    $o = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    if (($o | Where-Object { $_ -gt 255 }).Count -gt 0) { return $false }
    return ($o[0] -eq 10) -or ($o[0] -eq 172 -and $o[1] -ge 16 -and $o[1] -le 31) -or ($o[0] -eq 192 -and $o[1] -eq 168)
}

function Find-Ip {
    try {
        $config = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq "Up" } |
            Select-Object -First 1
        if ($config) { return [string]($config.IPv4Address | Select-Object -First 1).IPAddress }
    } catch { }
    return ""
}

function Ask($Question, $Default) {
    $answer = Read-Host "$Question [$Default]"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim()
}

function Initialize-Config {
    if (-not (Test-Path $Config)) {
        Copy-Item (Join-Path $DeployDir "config.env.example") $Config
        Say "Creado deploy\config.env"
    }
    foreach ($key in "POSTGRES_PASSWORD", "FUSIONAUTH_DB_ROOT_PASSWORD", "FUSIONAUTH_DB_PASSWORD") {
        if (-not (Get-Cfg $key)) { Set-Cfg $key (New-RandomHex 24) }
    }
    foreach ($key in "FUSIONAUTH_API_KEY", "FUSIONAUTH_CLIENT_SECRET", "AUTH_SECRET", "ANALYTICS_HMAC_KEY") {
        if (-not (Get-Cfg $key)) { Set-Cfg $key (New-RandomHex 32) }
    }
    foreach ($key in "FUSIONAUTH_ADMIN_PASSWORD", "OWNER_PASSWORD", "DEMO_OWNER_PASSWORD") {
        if (-not (Get-Cfg $key)) { Set-Cfg $key ("Ys-" + (New-RandomHex 8)) }
    }

    $current = Get-Cfg "HOST_IP"
    $detected = Find-Ip
    if (-not $current) {
        $default = if ($detected) { $detected } else { "192.168.1.10" }
        $chosen = Ask "IP de este PC en la red local (los celulares la usaran)" $default
    } elseif ($detected -and $detected -ne $current) {
        Write-Host "La IP configurada es $current pero este PC tiene ahora $detected."
        $chosen = Ask "IP a usar" $detected
    } else {
        $chosen = $current
    }
    if (-not (Test-PrivateIp $chosen)) { Die "HOST_IP '$chosen' no es una IP privada de red local (10.x, 172.16-31.x, 192.168.x)." }
    if ($chosen -ne $current) { Set-Cfg "HOST_IP" $chosen }

    if (-not (Get-Cfg "YOUTUBE_API_KEY")) {
        Write-Host "AVISO: YOUTUBE_API_KEY esta vacia en deploy\config.env: no habra busqueda de canciones ni datos demo." -ForegroundColor Yellow
    }
}

function Initialize-Repos {
    $root = (Resolve-Path (Join-Path $DeployDir "..\..")).Path
    $web = Join-Path $root "yousong-web"
    if (-not (Test-Path $web)) {
        Say "Clonando yousong-web en $root"
        git clone "$GitHub/yousong-web.git" $web
        if ($LASTEXITCODE -ne 0) { Die "No se pudo clonar yousong-web." }
    }
    $mobile = Join-Path $root "yousong-movile"
    if (-not (Test-Path $mobile)) { $mobile = Join-Path $root "yousong-mobile" }
    if (-not (Test-Path $mobile)) {
        Say "Clonando yousong-mobile en $root"
        git clone "$GitHub/yousong-mobile.git" $mobile
        if ($LASTEXITCODE -ne 0) { Die "No se pudo clonar yousong-mobile." }
    }
    $env:DEPLOY_DIR = $DeployDir
    $env:WEB_DIR = $web
    $env:MOBILE_DIR = $mobile
}

function Invoke-Compose {
    & docker compose --env-file $Config -f (Join-Path $DeployDir "compose.yaml") @args
    if ($LASTEXITCODE -ne 0) { Die "docker compose $($args -join ' ') fallo (codigo $LASTEXITCODE)." }
}

function Test-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Die "Docker Desktop no esta instalado (https://docs.docker.com/desktop/)." }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { Die "Docker no responde: abre Docker Desktop y espera a que diga 'Engine running'." }
}

function Wait-Healthy {
    Say "Esperando a que los servicios esten listos (la primera vez puede tardar varios minutos)"
    $deadline = (Get-Date).AddMinutes(15)
    while ($true) {
        $pending = @()
        foreach ($service in "postgres", "fusionauth", "backend", "analytics", "web", "download") {
            $line = (& docker compose --env-file $Config -f (Join-Path $DeployDir "compose.yaml") ps --format "{{.Health}} {{.State}}" $service 2>$null |
                Select-Object -First 1)
            if (-not ($line -like "healthy *" -or $line -eq " running")) { $pending += $service }
        }
        if ($pending.Count -eq 0) { return }
        if ((Get-Date) -gt $deadline) { Die "Servicios sin terminar de iniciar: $($pending -join ', '). Revisa: .\deploy.ps1 logs" }
        Start-Sleep -Seconds 5
    }
}

function Show-Summary {
    $ip = Get-Cfg "HOST_IP"
    $port = Get-Cfg "DOWNLOAD_PORT"
    if (-not $port) { $port = "8000" }
    Write-Host ""
    Write-Host "================================================================================"
    Write-Host " YouSong esta listo" -ForegroundColor Green
    Write-Host "--------------------------------------------------------------------------------"
    Write-Host " Celulares (misma red wifi):"
    Write-Host "   Descargar la app (QR):   http://${ip}:$port"
    Write-Host "   API que usa la app:      http://${ip}:8080"
    Write-Host " Solo en este PC:"
    Write-Host "   Panel del propietario:   http://localhost:3000"
    Write-Host "     usuario:  $(Get-Cfg 'OWNER_EMAIL')"
    Write-Host "     clave:    $(Get-Cfg 'OWNER_PASSWORD')"
    Write-Host "   Consola FusionAuth:      http://127.0.0.1:9011/admin"
    Write-Host "     usuario:  $(Get-Cfg 'FUSIONAUTH_ADMIN_EMAIL')"
    Write-Host "     clave:    $(Get-Cfg 'FUSIONAUTH_ADMIN_PASSWORD')"
    Write-Host " Datos de demostracion:     .\deploy.ps1 demo"
    Write-Host " Detener (conserva datos):  .\deploy.ps1 stop"
    Write-Host "================================================================================"
}

switch ($Command) {
    { $_ -in "up", "start", "ip" } {
        Test-Docker
        Initialize-Config
        Initialize-Repos
        Say "Construyendo e iniciando (la primera vez descarga varios GB)"
        Invoke-Compose up -d --build
        Wait-Healthy
        Show-Summary
    }
    "demo" {
        Test-Docker
        Initialize-Config
        Initialize-Repos
        Say "Cargando DATOS DE DEMOSTRACION (La Sala Demo)"
        Invoke-Compose --profile demo run --rm -T demo
        Say "Publicando la analitica"
        Invoke-Compose exec -T analytics python -m yousong_analytics once
        Write-Host ""
        Write-Host "Demo: entra a http://localhost:3000 con $(Get-Cfg 'DEMO_OWNER_EMAIL') / $(Get-Cfg 'DEMO_OWNER_PASSWORD')"
    }
    "status" { Initialize-Repos; Invoke-Compose ps }
    "logs" { Initialize-Repos; Invoke-Compose logs -f --tail 100 @Rest }
    "stop" { Initialize-Repos; Invoke-Compose down }
    "reset" {
        Initialize-Repos
        $answer = Read-Host "Esto BORRA todos los datos (bases de datos, APK, analitica). Escribe BORRAR para confirmar"
        if ($answer -ne "BORRAR") { Die "Cancelado." }
        Invoke-Compose --profile demo down -v
    }
    default { Die "Uso: .\deploy.ps1 [up|demo|ip|status|logs [servicio]|stop|reset]" }
}
