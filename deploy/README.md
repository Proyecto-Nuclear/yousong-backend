# Despliegue de YouSong para la presentación (Docker)

Un solo archivo de configuración (`config.env`) y un solo comando levantan todo YouSong en un PC: base de datos,
FusionAuth, backend, analítica, panel web del propietario, la APK de Android construida para la IP de ese PC y una
página con un QR para descargarla. Los celulares de la misma red wifi instalan la app y participan.

## Ruta rápida

| Paso | Linux / macOS | Windows (PowerShell) |
| --- | --- | --- |
| 1. Iniciar todo | `./deploy.sh` | `powershell -ExecutionPolicy Bypass -File .\deploy.ps1` |
| 2. (Opcional) datos demo | `./deploy.sh demo` | `powershell -ExecutionPolicy Bypass -File .\deploy.ps1 demo` |
| 3. Detener (conserva datos) | `./deploy.sh stop` | `... .\deploy.ps1 stop` |

Al terminar, el script imprime las direcciones y las claves:

| Qué | Dónde | Quién |
| --- | --- | --- |
| Página de descarga con el QR de la APK | `http://IP_DEL_PC:8000` | Celulares (proyéctala) |
| API que usa la app | `http://IP_DEL_PC:8080` | Celulares |
| Panel del propietario | `http://localhost:3000` | Solo este PC |
| Consola de FusionAuth | `http://127.0.0.1:9011/admin` | Solo este PC |

La red local solo ve los puertos 8080 (API) y 8000 (descarga). El panel y FusionAuth escuchan únicamente en
`127.0.0.1` y PostgreSQL no se publica.

## Requisitos

- **Docker**: Docker Desktop en Windows/macOS (con WSL 2 en Windows), Docker Engine + Compose v2 en Linux.
- **Git** (el script clona `yousong-web` y `yousong-mobile` si no están junto a `yousong-backend`).
- **8 GB de RAM** como mínimo y unos **15 GB libres** de disco.
- **Internet**: para construir (la primera vez descarga varios GB) y durante la presentación para YouTube
  (búsqueda de canciones y miniaturas).
- **Clave de YouTube Data API v3** (Google Cloud Console → APIs → YouTube Data API v3 → Credenciales).

Estructura esperada de carpetas (los nombres de las carpetas hermanas importan):

```text
alguna-carpeta/
├── yousong-backend/      ← este repositorio (deploy/ está aquí)
├── yousong-web/
└── yousong-movile/       (o yousong-mobile)
```

## Paso a paso

### Windows

1. Instala Docker Desktop y Git. Abre Docker Desktop y espera a que diga *Engine running*.
2. Abre PowerShell en `yousong-backend\deploy`.
3. Ejecuta `powershell -ExecutionPolicy Bypass -File .\deploy.ps1`.
4. La primera vez el script crea `config.env`, propone la IP del PC (Enter para aceptar) y genera las claves.
5. Abre `config.env` con el Bloc de notas, pega la clave en `YOUTUBE_API_KEY=` y vuelve a ejecutar el paso 3.
6. Abre el firewall para los celulares (PowerShell **como administrador**, una sola vez):

   ```powershell
   New-NetFirewallRule -DisplayName "YouSong" -Direction Inbound -Protocol TCP -LocalPort 8080,8000 -Action Allow -Profile Any
   ```

   Si Windows muestra el aviso de Docker Desktop pidiendo acceso a la red, acepta también **redes públicas**: la red de la
   universidad suele quedar como pública. Para quitar la regla después: `Remove-NetFirewallRule -DisplayName "YouSong"`.

### Linux

1. Instala Docker Engine con el plugin Compose y Git; tu usuario debe poder ejecutar `docker` (grupo `docker`).
2. En `yousong-backend/deploy`: `./deploy.sh`.
3. La primera vez se crea `config.env`; agrega `YOUTUBE_API_KEY` y ejecuta `./deploy.sh` de nuevo.
4. Firewall: Docker publica los puertos con sus propias reglas de iptables y **se salta ufw**, así que normalmente no hay
   que abrir nada. Si usas firewalld u otro firewall, permite TCP 8080 y 8000.

## Configuración (`config.env`)

| Variable | Para qué |
| --- | --- |
| `HOST_IP` | IP del PC en la red local. Se detecta sola; debe ser privada (10.x, 172.16-31.x, 192.168.x). |
| `YOUTUBE_API_KEY` | Catálogo de canciones. Sin ella la app funciona, pero no se puede buscar ni cargar la demo. |
| `DOWNLOAD_PORT` | Puerto de la página de descarga (8000). La API siempre usa 8080: la app solo acepta ese puerto. |
| `OWNER_EMAIL`, `DEMO_OWNER_EMAIL` | Cuentas del propietario y del propietario demo. |
| Claves vacías | Las genera el script una sola vez (bases de datos, FusionAuth, sesión web, analítica). |

Las claves se aplican al crear las bases de datos y FusionAuth. Cambiarlas después exige `reset`, que borra los datos.
`ANALYTICS_HMAC_KEY` no debe cambiar mientras existan datos: una clave nueva publica la misma participación dos veces.

## Cambiar de red o de IP (casa → universidad)

Vuelve a ejecutar `./deploy.sh` (o `.\deploy.ps1`). Si la IP del PC cambió, el script lo detecta y pregunta cuál usar;
luego reconstruye la APK para la IP nueva (sin internet, todo quedó descargado en la imagen). Los celulares deben
**descargar e instalar de nuevo** la APK desde la página: la IP va dentro de la app. Instalar encima conserva los datos
porque la APK siempre se firma con la misma llave (guardada en un volumen de Docker).

## Probar la red de la universidad antes de presentar

Muchas redes de universidad aíslan a los clientes: los celulares no pueden ver otros equipos aunque estén en la misma wifi.

1. Con todo iniciado, desde un celular conectado a la wifi abre `http://IP_DEL_PC:8000`.
2. Si la página carga, la red sirve. Prueba también `http://IP_DEL_PC:8080/actuator/health` (debe decir `UP`).
3. Si no carga: revisa el firewall de Windows; si sigue sin cargar, la red aísla clientes.

**Plan B** (sin aislamiento):

- Un router propio: conecta el PC y los celulares a ese router (no hace falta que tenga internet para la app, pero sí
  para YouTube; conéctalo por cable a la red de la universidad si se puede).
- La zona wifi (hotspot) de un celular: conecta el PC y los demás celulares a ella. Ejecuta el script otra vez para que tome
  la nueva IP. Algunos hotspots también aíslan clientes: pruébalo antes.

## Datos de demostración

`./deploy.sh demo` (o `.\deploy.ps1 demo`) crea el propietario demo (`DEMO_OWNER_EMAIL` / `DEMO_OWNER_PASSWORD`),
el local **La Sala Demo** y su historia, y publica la analítica:

- 8 noches cerradas de viernes y sábado de las últimas cuatro semanas (20:00 a 02:00, hora de Bogotá), cada una con
  unos 80 participantes (consentimientos WEB y ANDROID), unas 120 solicitudes en todos los estados, votos y
  reproducciones de solicitudes y de la lista de la casa con todos los resultados.
- Una noche abierta ahora mismo, con cola (pendientes, reservada y una sonando).
- Las canciones son reales: las trae la propia app desde YouTube (tendencias de Colombia y algunas búsquedas), con el
  género que reporta YouTube. Por eso necesita `YOUTUBE_API_KEY`.

Todo lo que crea está marcado como demo (nombres "demo" e "Invitado demo NN"). Ejecutarlo otra vez no duplica nada.
El propietario principal (`OWNER_EMAIL`) empieza sin local: créalo en vivo durante la presentación.

## Construir en casa y llevarlo listo

La primera construcción descarga varios GB (SDK de Android, Gradle, dependencias). Hazla en casa con buena conexión.

- **Mismo PC en la presentación**: no hay que hacer nada más; en la universidad solo ejecuta el script otra vez.
- **Otro PC**: copia las imágenes en un disco y cárgalas allá (evita descargar de nuevo):

  ```bash
  docker save -o yousong-imagenes.tar yousong-backend:local yousong-analytics:local yousong-web:local \
    yousong-apk-builder:local postgres:18 fusionauth/fusionauth-app:1.70.2 node:24-alpine nginx:1.27-alpine
  docker load -i yousong-imagenes.tar     # en el otro PC, antes de ejecutar el script
  ```

  Las imágenes ya construidas no se descargan de nuevo, pero el script igual compila lo que haya cambiado.

## Detener y reiniciar

| Acción | Comando |
| --- | --- |
| Ver estado | `./deploy.sh status` |
| Ver registros | `./deploy.sh logs` o `./deploy.sh logs backend` |
| Detener, conservando datos | `./deploy.sh stop` |
| Volver a iniciar | `./deploy.sh` |
| Borrar todos los datos | `./deploy.sh reset` (pide escribir BORRAR) |

En Windows es lo mismo con `.\deploy.ps1`.

## Problemas frecuentes

| Síntoma | Qué hacer |
| --- | --- |
| "port is already allocated" | Otro programa usa 3000, 8080, 9011 u 8000. Ciérralo (por ejemplo el backend de desarrollo). |
| El celular no abre la página | Firewall (ver arriba), misma wifi, red con aislamiento de clientes (plan B). |
| La app dice que el servicio no está configurado | La APK es de otra IP: descárgala de nuevo desde la página. |
| Android bloquea la instalación | Permite "instalar aplicaciones desconocidas" para el navegador. |
| Búsquedas responden "no disponible" | Falta `YOUTUBE_API_KEY`, no hay internet o se agotó la cuota diaria de YouTube (10.000 unidades). |
| El panel tarda la primera vez | Corre en modo desarrollo y compila cada página al abrirla por primera vez (segundos). |
| No puedo iniciar sesión en el panel | Usa `http://localhost:3000` en este PC (no la IP). Las claves están en `config.env`. |
| `demo` dice que falta el catálogo | Agrega `YOUTUBE_API_KEY`, ejecuta el script y luego `demo`. |
| Servicios que no terminan de iniciar | `./deploy.sh logs <servicio>`; en Windows dale a Docker Desktop al menos 6 GB de memoria. |

## Límites conocidos

- Es un despliegue de demostración en HTTP dentro de una red local: el panel corre en modo desarrollo y la APK es una
  compilación *debug* que solo acepta HTTP hacia la IP privada configurada. No es una instalación de producción.
- **Aviso de privacidad pendiente**: faltan los datos del responsable del tratamiento (nombre, NIT/identificación y
  canales de contacto) en la política que acepta cada participante. Complétalos antes de usar la app con personas reales
  fuera de la presentación.
- Sin correo (SMTP): las cuentas nuevas no verifican el correo.
