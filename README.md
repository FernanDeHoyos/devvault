# DevVault Backend

Backend de **DevVault**, una aplicación local para descubrir proyectos de desarrollo, iniciarlos, observar su estado y consultar información de su repositorio Git.

## Funcionalidades

- Registro de workspaces y exploración de proyectos en sus directorios.
- Detección de proyectos Spring Boot (Maven/Gradle), Node.js/React y PHP/Laravel.
- Catálogo estático de rutas para Spring MVC, Laravel, Express, Fastify y NestJS.
- Inicio y detención de proyectos locales o definidos con Docker Compose.
- Lectura de logs en tiempo real mediante WebSocket.
- Métricas y alertas de runtime, reglas de automatización y administración de plugins integrados.
- Apertura de un proyecto en el editor instalado (VS Code, IntelliJ, Sublime, Zed), configurable con `DEVVAULT_DEFAULT_EDITOR`.
- Resumen Git con ramas, commits, estado de cambios, relación con upstream y `fetch` manual.
- Autenticación local para un administrador, JWT con expiración/revocación y tickets de un solo uso para WebSocket.

## Stack

Java 17 · Spring Boot 3.4 · Spring Data JPA · PostgreSQL · Flyway · Docker Java · OSHI · WebSocket · Gradle.

## Requisitos

- JDK 17.
- Docker Desktop o Docker Engine para levantar PostgreSQL con Compose y para administrar proyectos Docker.
- Git disponible en `PATH` para las funciones Git.
- Node.js/npm o PHP/Composer instalados si se van a iniciar proyectos locales de esos stacks.

## Ejecución local

Desde la raíz de este repositorio, inicia PostgreSQL:

```bash
docker compose up -d postgres
```

Inicia la API con el wrapper de Gradle:

```bash
# Windows PowerShell
.\gradlew.bat bootRun

# Linux / macOS
./gradlew bootRun
```

La API queda disponible en `http://127.0.0.1:8080/api/v1`. Comprueba el estado del servicio y la base de datos en:

```text
GET http://127.0.0.1:8080/api/v1/health
```

Compose publica PostgreSQL 16 en el puerto `5433` del equipo (puerto `5432` dentro del contenedor), base de datos `devvault` y credenciales locales de desarrollo definidas en `docker-compose.yml`. Se usa `5433` para evitar conflictos con PostgreSQL instalado directamente en el equipo. La configuración de conexión de Spring está en `src/main/resources/application.yml`. Cambia esas credenciales antes de usar una base de datos accesible desde otras máquinas.

### Primer inicio y autenticación

No necesitas definir variables de autenticación para usar DevVault. En el primer inicio, abre la UI y crea un usuario y una contraseña de al menos 12 caracteres en el asistente de configuración. También genera una clave aleatoria de recuperación; guárdala fuera del equipo y confírmala antes de terminar. La clave se muestra una sola vez y DevVault conserva únicamente su hash. Si olvidas la contraseña, podrás cambiarla con esa clave desde la pantalla de recuperación. No hay registro público, tabla de usuarios ni roles.

Los perfiles de DevVault creados antes de añadir la recuperación no tienen todavía una clave configurada; usa el restablecimiento local descrito abajo para volver al asistente y crear una nueva.

DevVault guarda el hash BCrypt de la contraseña y una clave de firma JWT aleatoria en un archivo local fuera del repositorio: `%LOCALAPPDATA%\DevVault\auth.json` en Windows, `~/Library/Application Support/DevVault/auth.json` en macOS y `$XDG_CONFIG_HOME/devvault/auth.json` (o `~/.config/devvault/auth.json`) en Linux. El backend queda enlazado a `127.0.0.1` por defecto.

Si se pierde también la clave de recuperación, cierra el backend y elimina solo `%LOCALAPPDATA%\DevVault\auth.json` (Windows) para volver al asistente inicial. En macOS/Linux, elimina el `auth.json` de la ruta indicada arriba. Al iniciar de nuevo, DevVault generará otra clave de firma JWT y pedirá crear el administrador otra vez. Esto conserva los datos de proyectos en PostgreSQL, pero invalida las sesiones existentes. No uses este método si configuraste credenciales mediante variables de entorno.

Estas variables son opcionales para desarrollo avanzado o ejecución automatizada:

| Variable | Uso |
| --- | --- |
| `DEVVAULT_AUTH_USERNAME` y `DEVVAULT_AUTH_PASSWORD` | Credenciales para ejecución automatizada; deben configurarse juntas y deshabilitan el asistente inicial |
| `DEVVAULT_JWT_SECRET_BASE64` | Reemplaza la clave guardada; Base64 con al menos 32 bytes aleatorios |
| `DEVVAULT_AUTH_CONFIG_PATH` | Cambia la ubicación del archivo local de autenticación |
| `DEVVAULT_JWT_DURATION_MINUTES` | Vigencia del token (20 minutos por defecto, mínimo 5) |
| `DEVVAULT_DEFAULT_EDITOR` | Editor con el que se abren los proyectos: `vscode`, `vscode-insiders`, `intellij`, `sublime`, `zed` o `notepad` (por defecto `vscode`). Si no está instalado, DevVault usa el primero que detecte |
| `DEVVAULT_STARTUP_TIMEOUT_SECONDS` | Suelo del tiempo de espera del arranque de un proceso local (300 por defecto) |
| `DEVVAULT_PREVIEW_PORT` | Puerto de la preview empaquetada (5050 por defecto) |

El frontend guarda el JWT solo en `sessionStorage`; cerrar sesión lo revoca en el backend. El WebSocket de logs usa un ticket ligado al proyecto/servicio, de un solo uso y válido 30 segundos. El endpoint `/api/v1/health` queda público para comprobar disponibilidad.

Flyway aplica las migraciones al arrancar; Hibernate valida el esquema existente y no lo genera.

## Preview empaquetada para Windows

La preview se distribuye como un ZIP con UI y backend integrados, un runtime Java 17 reducido y scripts de inicio/cierre. Quien la usa no necesita instalar Java ni configurar variables de entorno. Sí necesita Docker Desktop instalado e iniciado: DevVault lo usa tanto para PostgreSQL como para administrar contenedores de proyectos.

Para generar el paquete desde el repositorio backend, con el repositorio UI hermano y las dependencias de Node instaladas:

```powershell
.\packaging\windows\Empaquetar-Preview.ps1
```

Si los repositorios no están uno junto al otro o el JDK no está en `JAVA_HOME`, se pueden indicar explícitamente:

```powershell
.\packaging\windows\Empaquetar-Preview.ps1 -UiPath "C:\ruta\devvault-ui" -JdkHome "C:\ruta\jdk-17" -Version "0.4.0-preview.1"
```

El ZIP resultante queda en `build\distribution`. El usuario lo extrae y ejecuta `Iniciar-DevVault.bat`; para cerrar usa `Detener-DevVault.bat`. La base de datos se guarda en un volumen Docker persistente y los registros del backend en `%LOCALAPPDATA%\DevVault\preview`. La guía incluida en el ZIP está en `LEEME.txt`.

## API principal

Base path: `/api/v1`.

| Área | Rutas principales |
| --- | --- |
| Workspaces | `/workspaces`, `/workspaces/{id}/scan` |
| Proyectos | `/projects`, `/projects/{id}`, `/projects/{id}/routes` |
| Runtime | `/projects/{id}/start`, `/stop`, `/status`, `/services` |
| Auth | `/auth/setup-status`, `/auth/setup`, `/auth/recover`, `/auth/login`, `/auth/me`, `/auth/logout`, `/auth/ws-ticket` |
| Logs | WebSocket `/projects/{id}/logs?service={serviceName}&ticket={ticket}` |
| Git | `/projects/{id}/git`, `/projects/{id}/git/fetch` |
| Monitoring | `/projects/{id}/metrics`, `/alerts` |
| Automation | `/automation/rules` |
| Plugins integrados | `/plugins` |
| Editor | `/editors`, `/projects/{id}/open` |

## Pruebas

```bash
# Windows
.\gradlew.bat test

# Linux / macOS
./gradlew test
```

Las pruebas de integración basadas en Testcontainers pueden requerir que Docker esté disponible.

## Estructura

El código se organiza por módulos funcionales bajo `src/main/java/com/devvault`: `workspace`, `discovery`, `runtime`, `monitoring`, `automation`, `plugin`, `auth`, `editor` y `shared`. Las migraciones SQL están en `src/main/resources/db/migration`.

## Alcance y seguridad

DevVault está pensado para ejecutarse localmente. La API requiere autenticación salvo login, health y el handshake validado por ticket. CORS permite el frontend local en `http://localhost:5050` y `http://127.0.0.1:5050`. No expongas el backend o la base de datos a redes no confiables con la configuración de desarrollo.
