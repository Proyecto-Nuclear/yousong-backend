#!/usr/bin/env bash
# YouSong presentation deployment (Linux/macOS). Usage: ./deploy.sh [up|demo|ip|status|logs [service]|stop|reset]
set -euo pipefail

cd "$(dirname "$0")"
DEPLOY_DIR="$(pwd)"
CONFIG="$DEPLOY_DIR/config.env"
GITHUB="https://github.com/Proyecto-Nuclear"

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

cfg_get() { [ -f "$CONFIG" ] && sed -n "s/^$1=//p" "$CONFIG" | tail -n 1 | tr -d '\r"' || true; }
cfg_set() {
    local tmp="$CONFIG.tmp"
    if grep -q "^$1=" "$CONFIG"; then
        awk -v k="$1" -v v="$2" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$CONFIG" > "$tmp"
    else
        cp "$CONFIG" "$tmp"
        printf '%s=%s\n' "$1" "$2" >> "$tmp"
    fi
    mv "$tmp" "$CONFIG"
    chmod 600 "$CONFIG"
}
random_hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

is_private_ip() {
    local ip="$1" a b c d
    [[ "$ip" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
    a=${BASH_REMATCH[1]}; b=${BASH_REMATCH[2]}; c=${BASH_REMATCH[3]}; d=${BASH_REMATCH[4]}
    for n in "$a" "$b" "$c" "$d"; do { [ "$n" -le 255 ] && [[ ! "$n" =~ ^0[0-9] ]]; } || return 1; done
    [ "$a" -eq 10 ] || { [ "$a" -eq 172 ] && [ "$b" -ge 16 ] && [ "$b" -le 31 ]; } || { [ "$a" -eq 192 ] && [ "$b" -eq 168 ]; }
}

detect_ip() {
    local ip=""
    if command -v ip > /dev/null 2>&1; then
        ip=$(ip -4 route get 1.1.1.1 2> /dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -n 1)
    fi
    if [ -z "$ip" ] && command -v route > /dev/null 2>&1 && command -v ipconfig > /dev/null 2>&1; then
        local iface
        iface=$(route -n get default 2> /dev/null | awk '/interface:/ { print $2 }')
        [ -n "$iface" ] && ip=$(ipconfig getifaddr "$iface" 2> /dev/null || true)
    fi
    printf '%s' "$ip"
}

# Asks only in an interactive terminal; otherwise keeps the default.
ask() {
    local question="$1" default="$2" answer=""
    if [ -t 0 ]; then
        read -r -p "$question [$default]: " answer || true
    fi
    printf '%s' "${answer:-$default}"
}

ensure_config() {
    if [ ! -f "$CONFIG" ]; then
        cp config.env.example "$CONFIG"
        chmod 600 "$CONFIG"
        say "Creado deploy/config.env"
    fi
    # Secrets: generated once, never overwritten.
    local key
    for key in POSTGRES_PASSWORD FUSIONAUTH_DB_ROOT_PASSWORD FUSIONAUTH_DB_PASSWORD; do
        [ -n "$(cfg_get $key)" ] || cfg_set "$key" "$(random_hex 24)"
    done
    [ -n "$(cfg_get FUSIONAUTH_API_KEY)" ] || cfg_set FUSIONAUTH_API_KEY "$(random_hex 32)"
    [ -n "$(cfg_get FUSIONAUTH_CLIENT_SECRET)" ] || cfg_set FUSIONAUTH_CLIENT_SECRET "$(random_hex 32)"
    [ -n "$(cfg_get AUTH_SECRET)" ] || cfg_set AUTH_SECRET "$(random_hex 32)"
    [ -n "$(cfg_get ANALYTICS_HMAC_KEY)" ] || cfg_set ANALYTICS_HMAC_KEY "$(random_hex 32)"
    for key in FUSIONAUTH_ADMIN_PASSWORD OWNER_PASSWORD DEMO_OWNER_PASSWORD; do
        [ -n "$(cfg_get $key)" ] || cfg_set "$key" "Ys-$(random_hex 8)"
    done

    local current detected chosen
    current=$(cfg_get HOST_IP)
    detected=$(detect_ip)
    if [ -z "$current" ]; then
        chosen=$(ask "IP de este PC en la red local (los celulares la usarán)" "${detected:-192.168.1.10}")
    elif [ -n "$detected" ] && [ "$detected" != "$current" ]; then
        printf '\nLa IP configurada es %s pero este PC tiene ahora %s.\n' "$current" "$detected"
        chosen=$(ask "IP a usar" "$detected")
    else
        chosen="$current"
    fi
    is_private_ip "$chosen" || die "HOST_IP '$chosen' no es una IP privada de red local (10.x, 172.16-31.x, 192.168.x)."
    [ "$chosen" = "$current" ] || cfg_set HOST_IP "$chosen"

    if [ -z "$(cfg_get YOUTUBE_API_KEY)" ]; then
        printf '\nAVISO: YOUTUBE_API_KEY está vacía en deploy/config.env: no habrá búsqueda de canciones ni datos demo.\n'
    fi
}

# Sibling repositories (cloned when missing). The mobile one may be named yousong-movile or yousong-mobile.
ensure_repos() {
    local root
    root=$(cd "$DEPLOY_DIR/../.." && pwd)
    if [ ! -d "$root/yousong-web" ]; then
        say "Clonando yousong-web en $root"
        git clone "$GITHUB/yousong-web.git" "$root/yousong-web"
    fi
    if [ -d "$root/yousong-movile" ]; then
        MOBILE_DIR="$root/yousong-movile"
    elif [ -d "$root/yousong-mobile" ]; then
        MOBILE_DIR="$root/yousong-mobile"
    else
        say "Clonando yousong-mobile en $root"
        git clone "$GITHUB/yousong-mobile.git" "$root/yousong-mobile"
        MOBILE_DIR="$root/yousong-mobile"
    fi
    WEB_DIR="$root/yousong-web"
    export DEPLOY_DIR WEB_DIR MOBILE_DIR
}

compose() { docker compose --env-file "$CONFIG" -f "$DEPLOY_DIR/compose.yaml" "$@"; }

check_docker() {
    command -v docker > /dev/null 2>&1 || die "Docker no está instalado (https://docs.docker.com/get-docker/)."
    docker compose version > /dev/null 2>&1 || die "Falta Docker Compose v2 ('docker compose')."
    docker info > /dev/null 2>&1 || die "Docker no responde: ¿está iniciado y tu usuario puede usarlo?"
}

wait_healthy() {
    say "Esperando a que los servicios estén listos (la primera vez puede tardar varios minutos)"
    local deadline=$((SECONDS + 900)) pending line
    while :; do
        pending=""
        for service in postgres fusionauth backend analytics web download; do
            line=$(compose ps --format '{{.Health}} {{.State}}' "$service" 2> /dev/null | head -n 1)
            case "$line" in
                "healthy "*|" running") ;;
                *) pending="$pending $service" ;;
            esac
        done
        [ -z "$pending" ] && return 0
        [ $SECONDS -lt $deadline ] || die "Servicios sin terminar de iniciar:$pending. Revisa: ./deploy.sh logs"
        sleep 5
    done
}

summary() {
    local ip port
    ip=$(cfg_get HOST_IP)
    port=$(cfg_get DOWNLOAD_PORT)
    port=${port:-8000}
    cat << EOF

================================================================================
 YouSong está listo
--------------------------------------------------------------------------------
 Celulares (misma red wifi):
   Descargar la app (QR):   http://$ip:$port
   API que usa la app:      http://$ip:8080
 Solo en este PC:
   Panel del propietario:   http://localhost:3000
     usuario:  $(cfg_get OWNER_EMAIL)
     clave:    $(cfg_get OWNER_PASSWORD)
   Consola FusionAuth:      http://127.0.0.1:9011/admin
     usuario:  $(cfg_get FUSIONAUTH_ADMIN_EMAIL)
     clave:    $(cfg_get FUSIONAUTH_ADMIN_PASSWORD)
 Datos de demostración:     ./deploy.sh demo
 Detener (conserva datos):  ./deploy.sh stop
================================================================================
EOF
}

cmd="${1:-up}"
case "$cmd" in
    up|start|ip)
        check_docker
        ensure_config
        ensure_repos
        say "Construyendo e iniciando (la primera vez descarga varios GB)"
        compose up -d --build
        wait_healthy
        summary
        ;;
    demo)
        check_docker
        ensure_config
        ensure_repos
        say "Cargando DATOS DE DEMOSTRACIÓN (La Sala Demo)"
        compose --profile demo run --rm -T demo < /dev/null
        say "Publicando la analítica"
        compose exec -T analytics python -m yousong_analytics once
        printf '\nDemo: entra a http://localhost:3000 con %s / %s\n' "$(cfg_get DEMO_OWNER_EMAIL)" "$(cfg_get DEMO_OWNER_PASSWORD)"
        ;;
    status)
        ensure_repos
        compose ps
        ;;
    logs)
        ensure_repos
        shift
        compose logs -f --tail 100 "$@"
        ;;
    stop)
        ensure_repos
        compose down
        ;;
    reset)
        ensure_repos
        answer=$(ask "Esto BORRA todos los datos (bases de datos, APK, analítica). Escribe BORRAR para confirmar" "")
        [ "$answer" = "BORRAR" ] || die "Cancelado."
        compose --profile demo down -v
        ;;
    *)
        die "Uso: ./deploy.sh [up|demo|ip|status|logs [servicio]|stop|reset]"
        ;;
esac
