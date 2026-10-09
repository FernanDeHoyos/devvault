# DevVault Backend

Backend de **DevVault**, una aplicación local para descubrir proyectos de desarrollo, iniciarlos, observar su estado y consultar información de su repositorio Git.

## Funcionalidades

- Registro de workspaces y exploración de proyectos en sus directorios.
- Quitar un workspace de DevVault: detiene lo que tenga en marcha y borra en cascada sus proyectos y datos, **sin tocar los archivos de la carpeta**.
- Detección de proyectos Spring Boot (Maven/Gradle), Node.js/React y PHP/Laravel.
- Catálogo estático de rutas para Spring MVC, Laravel, Express, Fastify y NestJS.
- Inicio y detención de proyectos locales o definidos con Docker Compose.
- Lectura de logs en tiempo real mediante WebSocket.
- Métricas y alertas de runtime, reglas de automatización y administración de plugins integrados.
- Apertura de un proyecto en el editor instalado (VS Code, IntelliJ, Sublime, Zed), configurable con `DEVVAULT_DEFAULT_EDITOR`.
- Resumen Git con ramas, commits, estado de cambios, relación con upstream y `fetch` manual.
- Sin autenticación: la API va abierta en la máquina del usuario y se protege enlazando a `127.0.0.1` y rechazando peticiones con un `Origin` ajeno.

## Stack

Java 17 · Spring Boot 3.4 · Spring Data JPA · PostgreSQL · Flyway · Docker Java · OSHI · WebSocket · Gradle.

## Requisitos

| Herramienta | Versión | Para qué |
| --- | --- | --- |
| JDK | 17 | Compilar y ejecutar el backend. Spring Boot 3.4 no arranca en 11 |
| Docker Desktop / Engine | reciente | PostgreSQL y para administrar contenedores de proyectos |
| Git | cualquiera en `PATH` | Funciones Git de DevVault |
| Node.js | `^20.19` o `>=22.12` | Solo para la UI. Lo exige Vite 8 |

Además, según lo que quieras arrancar: Node/npm o PHP/Composer para proyectos
locales de esos stacks, y un editor de código (VS Code, IntelliJ IDEA, Sublime
Text o Zed) para la función de abrir proyectos.

## Instalación paso a paso

### Atajo: un solo comando

Si ya clonaste los dos repositorios, esto es todo lo que necesitas:

```powershell
.\scripts\Iniciar-dev.ps1
```

Comprueba el JDK y Docker, levanta PostgreSQL, compila el backend
**incluyendo la UI ya compilada** del repositorio hermano, espera a que la API
responda y abre el navegador en `http://localhost:8080`. Para cerrar:

```powershell
.\scripts\Detener-dev.ps1
```

El script mide el tiempo de cada paso, así que sabes dónde se va el tiempo si
algo va lento.

| Opción | Para qué |
| --- | --- |
| `-Reuse` | Reutiliza el JAR si no cambiaste código Java. Ahorra ~15s |
| `-Port 8090` | Arranca en otro puerto si el 8080 está ocupado |
| `-NoBrowser` | No abre el navegador |
| `-UiDistPath` | Indica otra carpeta con la UI compilada |
| `Detener-dev.ps1 -StopDatabase` | Detiene también PostgreSQL |

> **Por qué no hace falta Node.** El repositorio `devvault-ui` versiona su
> carpeta `dist/`, así que un clon recién bajado ya incluye el frontend
> compilado y el script lo sirve desde el propio backend. Sin Node, sin
> `npm install`, sin segundo servidor de Vite y sin CORS, porque todo queda en el
> mismo origen. Para trabajar en la UI con recarga en caliente, usa
> `npm run dev` en `devvault-ui` y el 8080 como destino de la API.

Si prefieres el proceso manual, o si quieres recargar en caliente mientras
trabajas, sigue leyendo los pasos siguientes.

### 1. Clonar

La UI llega como **submódulo** dentro de este repositorio, así que un solo
`git clone` trae las dos partes:

```bash
git clone --recurse-submodules https://github.com/FernanDeHoyos/devvault.git
cd devvault
```

> Si ya clonaste sin `--recurse-submodules`, la carpeta `ui/` queda vacía. Se
> arregla sin volver a clonar:
>
> ```bash
> git submodule update --init --recursive
> ```

> **Por qué un submódulo y no copiar el código.** La UI sigue siendo un
> repositorio propio, pero el backend fija exactamente qué commit necesita. Así
> quien clone sabe qué versión de la interfaz va a ver, sin tener que adivinar, y
> el empaquetado ya no depende de que las carpetas queden juntas.

#### Trabajar en la UI

`ui/` es un repositorio normal dentro del otro. Para tocar el frontend:

```bash
cd ui
npm install
npm run dev          # http://localhost:5050, recarga en caliente, apunta al 8080
```

Los commits se hacen primero en `ui/` y después se actualiza el puntero en el
backend:

```bash
cd ui
git commit -am "feat: ..."
git push
cd ..
git add ui
git commit -m "chore: update UI submodule"
git push
```

Regenera `dist/` y súbelo junto a los cambios de la UI, porque es lo que
DevVault sirve en el arranque de un solo comando:

```bash
cd ui && npm run build && git add dist && git commit -m "build: rebuild dist"
```

### 2. Comprobar el JDK

```bash
java -version
```

Debe decir `17`. Si no lo dice, instala [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17)
y apunta `JAVA_HOME` a la carpeta raíz del JDK, no a su subcarpeta `bin`:

```powershell
# Windows PowerShell (usuario actual)
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-17.0.12_7", "User")
```

Abre una terminal nueva después de cambiarlo. También es el JDK con el que
DevVault va a **ejecutar los proyectos que invoques**, así que si tienes
proyectos que piden Java 21 o superior, ese JDK debe ser el de `JAVA_HOME`.

### 3. Levantar PostgreSQL

```bash
cd devvault
docker compose up -d postgres
```

Publica PostgreSQL 16 en el **puerto 5433** del equipo (5432 dentro del
contenedor), base de datos `devvault`, usuario `devvault` y contraseña
`devvault_dev`. Se usa 5433 a propósito, para no chocar con un PostgreSQL
instalado directamente en tu máquina. Los datos viven en el volumen Docker
`devvault_pgdata`.

Comprueba que está listo:

```bash
docker exec devvault-postgres pg_isready -U devvault -d devvault
```

### 4. Arrancar el backend

```bash
# Windows PowerShell
.\gradlew.bat bootRun

# Linux / macOS
./gradlew bootRun
```

La primera compilación descarga Gradle 9.5.1 y las dependencias: tarda. La API
queda en `http://127.0.0.1:8080/api/v1`.

### 5. Arrancar la UI

En otra terminal:

```bash
cd devvault-ui
npm ci
npm run dev
```

`npm ci` instala exactamente lo que dice `package-lock.json`, que es lo
recomendable frente a `npm install`. La UI queda en **http://localhost:5050**.

> El 5050 no es arbitrario. Los proyectos que DevVault administra suelen usar
> 3000, 4200, 5173 u 8080, que son los puertos por defecto de React, Angular, Vite
> y Spring Boot. DevVault nunca debe competir por el puerto de un proyecto que
> está intentando arrancar, así que `strictPort` hace que falle visiblemente en
> vez de saltar al siguiente puerto libre.

### 6. Abrir la interfaz

No hay asistente ni contraseña: DevVault abre directamente en el panel.

### 7. Verificar que todo responde

```bash
# Linux / macOS / Git Bash
curl -s http://127.0.0.1:8080/api/v1/health
```

En Windows PowerShell, `curl` es un alias de `Invoke-WebRequest` y no acepta
`-s`, así que usa el equivalente nativo:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
```

Cualquiera de los dos debe devolver algo como
`{"database":"Operative","status":"UP","timestamp":"..."}`. Si `database` no dice
`Operative`, el backend arrancó pero no ve a PostgreSQL.

### 8. Probar

```bash
cd devvault
./gradlew test        # o .\gradlew.bat test en Windows
```

## Puertos

| Puerto | Servicio | Nota |
| --- | --- | --- |
| 5050 | UI en desarrollo | Servidor de Vite |
| 8080 | API | Bind a `127.0.0.1` por defecto |
| 5433 | PostgreSQL de desarrollo | Publicado por `docker-compose.yml` |
| 5050 | Preview empaquetada | UI y API juntas en el mismo origen |
| 5434 | PostgreSQL de la preview | Separate, para no chocar con 5433 |

## Problemas frecuentes

**`Port 5050 is already in use`** — Tienes otro proceso en 5050. En PowerShell:

```powershell
Get-NetTCPConnection -LocalPort 5050 -State Listen | Select-Object OwningProcess
Stop-Process -Id <PID> -Force
```

**El backend no encuentra el JDK** — `java -version` responde bien pero Gradle
falla. Comprueba que `JAVA_HOME` apunte a la raíz del JDK y abre una terminal
nueva.

**Docker no responde** — DevVault necesita el daemon para PostgreSQL y para
administrar contenedores. Abre Docker Desktop y espera a que indique que está
listo.

**`error: release version 21 not supported`** — El proyecto que intentas
arrancar pide una versión de Java que tu equipo no tiene. Instálala y ajusta
`JAVA_HOME`. Los proyectos se compilan con el JDK de **tu** máquina, no con el
runtime incluido de la preview.

**Falla `WorkspaceControllerIT`** — Usa Testcontainers y necesita descargar
`postgres:16-alpine` de Docker Hub. En una red sin acceso al registro falla aunque
tengas la imagen descargada. No afecta al resto de la suite.

**El editor no aparece en la lista** — Solo se listan los que se detectan
instalados. Consulta `GET /api/v1/editors` para ver qué se detectó y con qué
ruta. Ajusta `DEVVAULT_DEFAULT_EDITOR` o pasa `?editorId=` en la llamada.

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

### Seguridad

DevVault no tiene autenticación. Es una decisión deliberada y conviene entender
qué cubre y qué no.

**Qué cubre.** El backend enlaza a `127.0.0.1` por defecto, así que la API solo
es alcanzable desde el propio equipo, no desde la red. Además `SameOriginFilter`
devuelve `403` ante cualquier petición que llegue con una cabecera `Origin` que
no sea un origen propio.

**Por qué hace falta el filtro si ya está en loopback.** La amenaza realista no
es un atacante remoto: es cualquier página web que estés mirando. CORS impide
que esa página *lea* la respuesta de la API, pero no impide que la *petición* se
ejecute. Una petición "simple" —la que no lleva cabeceras raras ni cuerpo
tipado— se envía igualmente. Sin el filtro, visitar una página cualquiera podría
acabar en `POST /api/v1/projects/{id}/stop` (que no lleva cuerpo, luego es
simple) y parar tus proyectos, o en `POST /api/v1/projects/{id}/open` y lanzar
tu editor.

**Qué no cubre.** Cualquier programa que se ejecute en tu cuenta puede hablar
con la API sin restricción, porque el filtro solo mira la cabecera `Origin` y las
peticiones sin `Origin` se dejan pasar (las de `curl`, Postman o cualquier
cliente de línea de comandos). Y si cambias `server.address` para escuchar en
`0.0.0.0`, la API queda accesible a la red sin ninguna autenticación.

**No expongas el backend a redes no confiables.** Es una herramienta local, no un
servicio. Si necesitas accessing from another machine, usa un túnel o un proxy
con autenticación propia, no abras el puerto.

### Variables de entorno

Todas son opcionales para desarrollo avanzado o ejecución automatizada:

| Variable | Uso |
| --- | --- |
| `DEVVAULT_DEFAULT_EDITOR` | Editor con el que se abren los proyectos: `vscode`, `vscode-insiders`, `intellij`, `sublime`, `zed` o `notepad` (por defecto `vscode`). Si no está instalado, DevVault usa el primero que detecte |
| `DEVVAULT_STARTUP_TIMEOUT_SECONDS` | Suelo del tiempo de espera del arranque de un proceso local (300 por defecto) |
| `DEVVAULT_PREVIEW_PORT` | Puerto de la preview empaquetada (5050 por defecto) |

El frontend llama a la API por ruta relativa en la preview y a `127.0.0.1:8080` en desarrollo. El WebSocket de logs se abre sobre el mismo origen. El endpoint `/api/v1/health` queda disponible para comprobar disponibilidad.

Flyway aplica las migraciones al arrancar; Hibernate valida el esquema existente y no lo genera.

## Preview empaquetada para Windows

La preview se distribuye como un ZIP con UI y backend integrados, un runtime Java 17 reducido y scripts de inicio/cierre. Quien la usa no necesita instalar Java ni configurar variables de entorno. Sí necesita Docker Desktop instalado e iniciado: DevVault lo usa tanto para PostgreSQL como para administrar contenedores de proyectos.

Para generar el paquete desde este repositorio, con la UI disponible en el
submódulo `ui/` y sus dependencias de Node instaladas:

```powershell
.\packaging\windows\Empaquetar-Preview.ps1
```

Si solo cambiaste el backend, o no quieres depender de Node en la máquina que
empaqueta, reutiliza la UI ya compilada:

```powershell
.\packaging\windows\Empaquetar-Preview.ps1 -SkipUiBuild
```

Si la UI está en otro sitio o el JDK no está en `JAVA_HOME`, indícalo explícito:

```powershell
.\packaging\windows\Empaquetar-Preview.ps1 -UiPath "C:\ruta\devvault-ui" -JdkHome "C:\ruta\jdk-17" -Version "0.4.0-preview.1"
```

El ZIP resultante queda en `build\distribution`. El usuario lo extrae y ejecuta `Iniciar-DevVault.bat`; para cerrar usa `Detener-DevVault.bat`. La base de datos se guarda en un volumen Docker persistente y los registros del backend en `%LOCALAPPDATA%\DevVault\preview`. La guía incluida en el ZIP está en `LEEME.txt`.

## API principal

Base path: `/api/v1`.

| Área | Rutas principales |
| --- | --- |
| Workspaces | `/workspaces`, `/workspaces/{id}/scan`, `DELETE /workspaces/{id}` |
| Proyectos | `/projects`, `/projects/{id}`, `/projects/{id}/routes` |
| Runtime | `/projects/{id}/start`, `/stop`, `/status`, `/services`, `/runtime/active` |
| Logs | WebSocket `/projects/{id}/logs?service={serviceName}` |
| Git | `/projects/{id}/git`, `/projects/{id}/git/fetch` |
| Monitoring | `/projects/{id}/metrics`, `/alerts` |
| Automation | `/automation/rules` |
| Plugins integrados | `/plugins` |
| Editor | `/editors`, `/projects/{id}/open` |
| Salud | `/health` |

Resource Management, Environment diffing y `POST /projects/{id}/restart` están
diseñados en el contrato de API pero todavía no implementados. El recuento
completo está en [DevVault-API-Design.md](docs/DevVault-API-Design.md).

## Pruebas

```bash
# Windows
.\gradlew.bat test

# Linux / macOS
./gradlew test
```

Las pruebas de integración basadas en Testcontainers pueden requerir que Docker esté disponible.

## Estructura

El código se organiza por módulos funcionales bajo `src/main/java/com/devvault`: `workspace`, `discovery`, `runtime`, `monitoring`, `automation`, `plugin`, `editor` y `shared`. La interfaz vive en el submódulo `ui/`. Las migraciones SQL están en `src/main/resources/db/migration`.

## Alcance y seguridad

DevVault está pensado para ejecutarse localmente y no lleva autenticación. La API enlaza a `127.0.0.1` por defecto y `SameOriginFilter` rechaza las peticiones con un `Origin` ajeno. Lee [Seguridad](#seguridad) para qué cubre y qué no. No expongas el backend a redes no confiables.
