# DevVault — Diseño de API REST (v1)

## Convenciones generales

- **Base path:** `/api/v1`
- **Formato:** JSON en request y response, `Content-Type: application/json`
- **Auth:** todos los endpoints `/api/**` requieren `Authorization: Bearer {accessToken}`, excepto login, setup y recuperación local (solo loopback), health y el handshake de logs autorizado con ticket de un solo uso.
- **Configuración inicial:** el primer inicio permite crear un único administrador desde loopback. La contraseña y la clave de recuperación se almacenan como hashes; la clave JWT se genera y persiste en la carpeta de configuración del usuario.
- **IDs:** UUID en formato string
- **Fechas:** ISO-8601 con timezone (`2026-08-13T14:30:00Z`)
- **Paginación** (en endpoints de listado que puedan crecer mucho): query params `page` (default 0) y `size` (default 20), respuesta envuelta:
```json
{
  "content": [ /* items */ ],
  "page": 0,
  "size": 20,
  "totalElements": 47,
  "totalPages": 3
}
```
- **Formato de error estándar** (todos los endpoints, cualquier código 4xx/5xx):
```json
{
  "timestamp": "2026-08-13T14:30:00Z",
  "status": 422,
  "error": "UNPROCESSABLE_ENTITY",
  "message": "La ruta indicada no existe o no es accesible",
  "path": "/api/v1/workspaces"
}
```
- **Operaciones asíncronas** (escaneo, start/stop): responden `202 Accepted` inmediatamente y exponen un endpoint de estado para hacer polling, en vez de bloquear la petición.

---

## 1. Identity

### `GET /auth/setup-status`
Indica si falta crear el administrador local. Es público para dirigir la UI al asistente inicial.
**Response `200`:** `{ "setupRequired": true, "recoveryConfigured": false }`

### `POST /auth/setup`
Crea el administrador local una sola vez. Solo acepta conexiones loopback; username de 3–64 caracteres, contraseña de 12–128 y clave de recuperación aleatoria confirmada por la UI.
**Request:** `{ "username": "dev", "password": "••••••••••••", "recoveryKey": "<clave aleatoria>" }`
**Response:** `204 No Content` · `409` si ya se configuró un administrador.

### `POST /auth/recover`
Restablece la contraseña local con username y clave de recuperación. Solo acepta conexiones loopback. Incrementa la versión de credenciales, invalidando de inmediato los JWT de sesiones anteriores.
**Request:** `{ "username": "dev", "recoveryKey": "<clave aleatoria>", "newPassword": "••••••••••••" }`
**Response:** `204 No Content` · `401` si la clave no coincide o no hay una clave configurada.

### `POST /auth/login`
Autentica al administrador local configurado en el primer inicio y devuelve un JWT.

**Request:**
```json
{ "username": "dev", "password": "••••••••" }
```
**Response `200`:**
```json
{ "accessToken": "eyJhbGciOi...", "tokenType": "Bearer", "expiresAt": "...", "username": "dev" }
```
JWT HS256, emisor `devvault`, vigencia por defecto de 20 minutos.
**Errores:** `401` credenciales inválidas (mensaje genérico).

### `POST /auth/logout`
Revoca server-side el JWT actual hasta que expire.
**Response:** `204 No Content`

### `GET /auth/me`
Devuelve el nombre del administrador autenticado.
**Response `200`:**
```json
{ "username": "dev" }
```
**Errores:** `401` sin token o token inválido

### `POST /auth/ws-ticket`
Emite un ticket aleatorio, de un solo uso y válido durante 30 segundos para un par proyecto/servicio. El cliente lo presenta en la query del WebSocket de logs; el JWT nunca se envía en la URL.
**Request:** `{ "projectId": "uuid", "serviceName": "api" }`
**Response `200`:** `{ "ticket": "...", "expiresAt": "..." }`

---

## 2. Workspace

### `POST /workspaces`
Crea un Workspace apuntando a una ruta local (HU-02). `name` es opcional; si se omite o está vacío, se toma el nombre de la carpeta seleccionada.

**Request:**
```json
{ "name": "Mi PC — Development", "path": "D:/Development" }
```
También se acepta `{ "path": "D:/Development" }` para usar el nombre de la carpeta.
**Response `201`:**
```json
{ "id": "uuid", "name": "Mi PC — Development", "path": "D:/Development", "createdAt": "..." }
```
**Errores:**
- `422` ruta inaccesible o inexistente
- `409` ya existe un Workspace con esa misma ruta (constraint `idx_workspaces_path`)

### `GET /workspaces`
Lista los Workspaces del usuario (HU-03).
**Response `200`:** array de Workspace (ver arriba), o `[]` si no tiene ninguno.

### `GET /workspaces/{id}`
Detalle de un Workspace, incluyendo conteo de proyectos.
**Response `200`:**
```json
{ "id": "uuid", "name": "...", "path": "...", "createdAt": "...", "projectCount": 6, "lastScanAt": "..." }
```
**Errores:** `404` no existe

### `GET /workspaces/directories?path={path}`
Lista las carpetas de la máquina donde corre DevVault para navegar hasta una carpeta desde la UI. Sin `path`, inicia en el directorio personal del usuario (`user.home`); si no está disponible, devuelve las raíces del sistema. `parentPath` permite subir un nivel por vez.
**Response `200`:** `{ "currentPath": "C:/Users/user", "parentPath": "C:/Users", "directories": [{ "name": "Documents", "path": "C:/Users/user/Documents" }] }`

### `POST /workspaces/{id}/scan`
Dispara el Scanner Engine de forma asíncrona (HU-04, CU-03).
**Response `202`:**
```json
{ "scanId": "uuid", "status": "IN_PROGRESS" }
```
**Errores:** `404` Workspace no existe · `409` ya hay un escaneo en curso para este Workspace

### `GET /workspaces/{id}/scan/status`
Consulta el estado del último escaneo (polling).
**Response `200`:**
```json
{ "scanId": "uuid", "status": "IN_PROGRESS", "projectsFound": 4, "startedAt": "...", "finishedAt": null }
```
`status` ∈ `IN_PROGRESS | COMPLETED | FAILED`

### `DELETE /workspaces/{id}`
Elimina el Workspace (cascada sobre sus Project, por diseño del ERD).
**Response:** `204` · **Errores:** `404`

---

## 3. Project

### `GET /projects`
Lista proyectos, con filtros opcionales (HU-07).
**Query params:** `workspaceId`, `status` (`ACTIVE|NOT_FOUND|ARCHIVED`), `language`, `framework`, `page`, `size`
**Response `200`:** listado paginado de:
```json
{
  "id": "uuid", "name": "BarberSaaS", "language": "Java",
  "framework": "Spring Boot", "version": "3.3", "status": "ACTIVE",
  "workspaceId": "uuid"
}
```

### `GET /projects/{id}`
Detalle completo, incluyendo el `ProjectProfile` (HU-08, CU-04).
**Response `200`:**
```json
{
  "id": "uuid", "name": "BarberSaaS", "path": "D:/Development/BarberSaaS",
  "language": "Java", "framework": "Spring Boot", "version": "3.3", "status": "ACTIVE",
  "profile": { "detectedAt": "...", "rawMarkers": { "pom.xml": true, "docker-compose.yml": true } }
}
```
**Errores:** `404`

### `GET /projects/{id}/routes`
Devuelve el catálogo estático de rutas HTTP detectadas en el código fuente del proyecto. Incluye método, ruta, controlador/handler, archivo y línea de origen. Soporta mappings de Spring MVC, declaraciones Laravel en `routes/*.php` y patrones habituales de Express, Fastify y NestJS.

**Response `200`:** lista de `ProjectRouteResponse`; cada elemento indica `STATIC` o `CONDITIONAL` cuando la ruta depende de anotaciones Spring reconocidas.

> El catálogo no ejecuta la aplicación. Las rutas construidas dinámicamente, registradas mediante patrones no soportados o condicionadas por lógica/configuración que el escáner no reconoce pueden no aparecer. Las rutas `resource` de Laravel se muestran como una declaración compacta, no como cada verbo generado por el framework.

### `GET /projects/{id}/git`
Consulta el estado Git local del proyecto: rama y commit actuales, cambios preparados/modificados/sin seguimiento, relación ahead/behind con upstream, ramas locales/remotas, hasta 50 commits y las últimas ejecuciones de `fetch` iniciadas desde DevVault.

### `POST /projects/{id}/git/fetch`
Ejecuta `git fetch --all --prune` usando la configuración de Git del usuario. Actualiza referencias remotas; no integra cambios en los archivos de trabajo. DevVault registra los intentos exitosos y fallidos.
**Errores:** `404` proyecto inexistente, `409` sin remotos, `422` no es un repositorio Git, `502` error de conexión/autenticación, `504` tiempo agotado.

### `DELETE /projects/{id}`
Archiva o elimina un proyecto detectado manualmente (fuera de un re-escaneo).
**Response:** `204`

---

## 4. Runtime

### `POST /projects/{id}/start`
Inicia el entorno del proyecto (CU-05). Asíncrono.
**Precondición:** `docker-compose.yml` detectado y Docker corriendo en el host.
**Response `202`:**
```json
{ "runtimeInstanceId": "uuid", "status": "STARTING" }
```
**Errores:**
- `409` el proyecto ya está `RUNNING` (idempotente: se devuelve el estado actual en vez de relanzar, no es realmente un error, ver nota)
- `422` no se detectó `docker-compose.yml`
- `503` Docker no está disponible en el host

> Nota de diseño: en vez de `409`, considera devolver `200` con el `runtimeInstanceId` existente cuando ya está corriendo — es más fiel a la regla de negocio "operación idempotente" definida antes. Decide esto según si el frontend necesita distinguir "ya estaba corriendo" de "se acaba de iniciar".

### `POST /projects/{id}/stop`
Detiene el entorno (CU-06).
**Response `202`:** `{ "status": "STOPPING" }`
**Errores:** `409` el proyecto no tiene una instancia activa

### `POST /projects/{id}/restart`
Azúcar sintáctico sobre stop + start.
**Response `202`:** igual que start

### `GET /projects/{id}/status`
Estado agregado del `RuntimeInstance` actual.
**Response `200`:**
```json
{ "overallStatus": "RUNNING", "startedAt": "...", "services": [
  { "name": "app-backend", "status": "RUNNING" },
  { "name": "postgres", "status": "FAILED", "reason": "Health check timeout after 30s" },
  { "name": "redis", "status": "RUNNING" }
]}
```

### `GET /projects/{id}/services`
Lista los `Service` + `Container` del proyecto. Desde la extensión de ejecución local (ver sección 5.1 del documento de diseño), la respuesta es polimórfica según `kind`:

**Response `200`:** array de:
```json
// Un servicio Docker
{ "id": "uuid", "name": "postgres", "type": "DATABASE", "port": 5432, "status": "RUNNING",
  "kind": "DOCKER", "pid": null, "command": null, "dockerContainerId": "docker-abc123" }

// Un servicio de proceso local (ej. npm run dev)
{ "id": "uuid", "name": "app", "type": "APP", "port": 5174, "status": "RUNNING",
  "kind": "LOCAL_PROCESS", "pid": 22976, "command": "npm.cmd run dev", "dockerContainerId": null }
```
`port` refleja el puerto **real** detectado en la salida del proceso (no necesariamente el asumido por convención — ver nota de diseño en 5.1 sobre por qué el puerto asumido no es confiable).

### `WS /projects/{id}/logs?service={serviceName}&ticket={ticket}`
Canal WebSocket de streaming de logs en vivo (CU-07). Requiere un ticket recién emitido por `POST /auth/ws-ticket`; un ticket solo permite un handshake y queda ligado al proyecto/servicio.
**Mensajes emitidos (servidor → cliente):**
```json
{ "timestamp": "...", "level": "INFO", "message": "Started BarberApplication in 2.1s" }
```
**Cierre del canal:** si el contenedor se cae, el servidor envía un mensaje de cierre con el motivo antes de cerrar el socket.

---

## 5. Resource

### `POST /resources`
Registra un recurso compartido (CU-08). Las credenciales se cifran antes de persistir.
**Request:**
```json
{ "name": "Postgres Local", "type": "DATABASE", "host": "localhost", "port": 5432,
  "credentials": { "username": "postgres", "password": "•••" } }
```
**Response `201`:** igual sin el campo `credentials` en texto plano — nunca se devuelve el valor, solo `credentialsSet: true`

### `GET /resources`
Lista recursos registrados. **Nunca** incluye credenciales en la respuesta.

### `GET /resources/{id}`
Detalle de un recurso (sin credenciales).

### `DELETE /resources/{id}`
**Errores:** `409` si el recurso sigue asociado a algún proyecto (regla `ON DELETE RESTRICT`) — el mensaje debe indicar qué proyectos lo usan.

### `POST /projects/{id}/resources/{resourceId}`
Asocia un recurso a un proyecto (CU-09). Idempotente.
**Response:** `204`

### `DELETE /projects/{id}/resources/{resourceId}`
Desasocia. **Response:** `204`

---

## 6. Environment

### `GET /projects/{id}/environment`
Lista los archivos de entorno detectados y sus variables (solo claves, nunca valores si `isSecret`).
**Response `200`:**
```json
[{ "kind": "ENV", "path": ".env", "variables": [
  { "key": "DATABASE_URL", "present": true, "isSecret": false },
  { "key": "JWT_SECRET", "present": true, "isSecret": true }
]}]
```

### `GET /projects/{id}/environment/diff`
Compara contra la plantilla (`.env.example`) y devuelve lo faltante (CU-10).
**Response `200`:**
```json
{ "missing": ["JWT_SECRET", "REDIS_URL"], "extra": ["OLD_UNUSED_VAR"] }
```

---

## 7. Automation

### `POST /automation/rules`
Crea una regla (CU-11).
**Request:**
```json
{
  "name": "Registrar fallo de arranque",
  "projectId": null,
  "triggers": [{ "eventType": "ProjectFailedEvent" }],
  "conditions": [{ "expression": "#reason.contains('puerto')" }],
  "actions": [{ "actionType": "LOG", "params": { "message": "Falló #{projectId}: #{reason}" } }]
}
```
**Response `201`:** la regla creada con su `id`
**Eventos implementados:** `ProjectFailedEvent` (fallo de inicio local o Docker) y `ProjectStartedEvent` (inicio correcto local o Docker). El contexto comparte `#projectId`; `#reason` solo está disponible al fallar.
**Acciones implementadas:** `LOG`, escribe el mensaje en el log del backend. Todavía no envía notificaciones ni ejecuta comandos.
**Errores:** `400` si no se envía al menos un `trigger` y una `action` (invariante de negocio).

### `GET /automation/rules`
Lista todas las reglas. El alcance por proyecto se configura con `projectId`; `null` significa todos los proyectos.

### `GET /automation/rules/{id}`
Detalle completo con triggers, condiciones y acciones.

### `PATCH /automation/rules/{id}`
Habilita/deshabilita o edita una regla.
**Request:** `{ "isEnabled": false }`
**Response `200`:** regla actualizada

### `DELETE /automation/rules/{id}`
**Response:** `204`

---

## 8. Monitoring

### `GET /projects/{id}/metrics`
Muestra CPU/RAM de los runtimes del proyecto: contenedores Docker y procesos locales. Para procesos locales se mide el árbol descendiente del PID raíz con OSHI. CPU se calcula comparando muestras consecutivas. Las métricas se consultan bajo demanda y no se conserva historial. Docker debe estar disponible para medir contenedores.
**Query params:** `serviceId` (opcional)
**Response `200`:**
```json
[{ "serviceId": "uuid", "serviceName": "api", "runtimeKind": "DOCKER", "containerId": "docker-id", "pid": null, "cpuPercent": 12.4, "memMb": 340, "capturedAt": "..." },
 { "serviceId": "uuid", "serviceName": "web", "runtimeKind": "LOCAL_PROCESS", "containerId": null, "pid": 1234, "cpuPercent": 4.8, "memMb": 120, "capturedAt": "..." }]
```
`cpuPercent` puede ser `null` si el sistema operativo no entrega datos válidos en ambas muestras. `memMb` representa memoria residente agregada del runtime; puede haber pequeñas diferencias entre sistemas operativos.

### `GET /alerts`
Alertas activas o resueltas, persistidas a partir de `ProjectFailedEvent`. Los fallos de arranque de Docker y procesos locales generan el mismo tipo de alerta (`PROJECT_FAILED`).
**Query params:** `status` (`ACTIVE|RESOLVED`), `projectId`
**Response `200`:** array de `Alert`

### `PATCH /alerts/{id}`
Marca una alerta como resuelta manualmente.
**Request:** `{ "resolved": true }`
**Response `200`:** alerta actualizada con `resolvedAt`

---

## 9. Plugin System

En el estado actual, estos endpoints administran los plugins incluidos y registrados en el classpath de DevVault. No instalan JARs, cargan código en caliente ni representan todavía una API pública para plugins externos.

### `GET /plugins`
Lista los `PluginDescriptor` registrados (detectores de tecnología).
**Response `200`:**
```json
[{ "id": "uuid", "name": "spring-boot-detector", "targetMarkerFiles": "pom.xml,build.gradle,build.gradle.kts", "version": "1.0", "enabled": true }]
```

### `PATCH /plugins/{id}`
Habilita/deshabilita un plugin de detección.
**Request:** `{ "enabled": false }`

---

## 9bis. Editor de código

Abre un proyecto en el editor instalado en la máquina. El editor se lanza como
proceso desacoplado: no se registra en el runtime, no genera `RuntimeInstance` ni
`Container`, no publica eventos y no se puede detener desde DevVault. El sistema
operativo es su dueño y sobrevive al cierre de DevVault.

El editor se resuelve por orden: el que indica la petición → el configurado en
`devvault.editor.default-editor` → el primero disponible que se detecte. Si el
elegido no está instalado, la operación falla con `422` en vez de abrir en otro.

### `GET /editors`
Lista los editores conocidos y cuáles están disponibles en esta máquina. No
ejecuta nada: solo comprueba que el ejecutable exista, para no abrir una ventana
del IDE cada vez que la UI pinta la lista.
**Response `200`:**
```json
{
  "editors": [
    { "id": "vscode", "displayName": "Visual Studio Code", "available": true,
      "executablePath": "C:\\Users\\user\\AppData\\Local\\Programs\\Microsoft VS Code\\bin\\code.cmd", "isDefault": true },
    { "id": "intellij", "displayName": "IntelliJ IDEA", "available": false,
      "executablePath": null, "isDefault": false }
  ],
  "defaultId": "vscode",
  "configuredId": "vscode"
}
```
`defaultId` es el que se usaría ahora mismo. `configuredId` es lo que dice la
configuración, que puede no coincidir con `defaultId` si el configurado no está
instalado.

### `POST /projects/{id}/open`
Abre la carpeta del proyecto en el editor. El editor va como query param, no en
el cuerpo: es un escalar único y así el endpoint se invoca sin argumentos.
**Response `200`:** `{ "projectId": "uuid", "projectName": "...", "editorId": "vscode", "editorName": "Visual Studio Code", "executablePath": "..." }`
**Errores:**
- `404` el proyecto no existe
- `422` la carpeta del proyecto ya no está en disco (workspace obsoleto, hay que reescanear)
- `422` editor desconocido, o conocido pero no instalado en esta máquina

> El editor se busca como nombre en el `PATH`, como ruta absoluta (expandiendo
> `%VARIABLE%` de Windows) o con un comodín, porque la carpeta de instalación de
> IntelliJ incluye la versión. Los editores de terminal (Neovim, Vim) no están:
> sin consola adjunta no sobreviven a `ProcessBuilder`.

---

## 10. Resumen de endpoints (referencia rápida)

| Método | Ruta | Módulo | Caso de uso |
|---|---|---|---|
| POST | `/auth/login` | Identity | CU-01 |
| GET | `/auth/setup-status` | Identity | CU-01 |
| POST | `/auth/setup` | Identity | CU-01 |
| POST | `/auth/recover` | Identity | Recuperar el acceso local con la clave de recuperación |
| POST | `/auth/logout` | Identity | CU-02 |
| GET | `/auth/me` | Identity | — |
| POST | `/auth/ws-ticket` | Identity | CU-07 |
| POST | `/workspaces` | Workspace | CU-03 |
| GET | `/workspaces` | Workspace | — |
| GET | `/workspaces/{id}` | Workspace | — |
| POST | `/workspaces/{id}/scan` | Workspace | CU-03 |
| GET | `/workspaces/{id}/scan/status` | Workspace | CU-03 |
| DELETE | `/workspaces/{id}` | Workspace | — |
| GET | `/projects` | Discovery | CU-04 |
| GET | `/projects/{id}` | Discovery | CU-04 |
| GET | `/projects/{id}/routes` | Discovery | — |
| GET | `/projects/{id}/git` | Git | — |
| POST | `/projects/{id}/git/fetch` | Git | — |
| DELETE | `/projects/{id}` | Discovery | — |
| POST | `/projects/{id}/start` | Runtime | CU-05 |
| POST | `/projects/{id}/stop` | Runtime | CU-06 |
| POST | `/projects/{id}/restart` | Runtime | CU-05/06 |
| GET | `/projects/{id}/status` | Runtime | CU-05 |
| GET | `/projects/{id}/services` | Runtime | CU-07 |
| WS | `/projects/{id}/logs` | Runtime | CU-07 |
| POST | `/resources` | Resource | CU-08 |
| GET | `/resources` | Resource | — |
| GET | `/resources/{id}` | Resource | — |
| DELETE | `/resources/{id}` | Resource | — |
| POST | `/projects/{id}/resources/{resourceId}` | Resource | CU-09 |
| DELETE | `/projects/{id}/resources/{resourceId}` | Resource | — |
| GET | `/projects/{id}/environment` | Environment | CU-10 |
| GET | `/projects/{id}/environment/diff` | Environment | CU-10 |
| POST | `/automation/rules` | Automation | CU-11 |
| GET | `/automation/rules` | Automation | — |
| GET | `/automation/rules/{id}` | Automation | — |
| PATCH | `/automation/rules/{id}` | Automation | — |
| DELETE | `/automation/rules/{id}` | Automation | — |
| GET | `/projects/{id}/metrics` | Monitoring | CU-13 |
| GET | `/alerts` | Monitoring | CU-13 |
| PATCH | `/alerts/{id}` | Monitoring | — |
| GET | `/plugins` | Plugin | — |
| PATCH | `/plugins/{id}` | Plugin | — |
| GET | `/editors` | Editor | — |
| POST | `/projects/{id}/open` | Editor | — |

**Total: 42 endpoints** (41 REST + 1 WebSocket), incluyendo configuración inicial, recuperación, autorización de logs y apertura en el editor.

---

## 11. Alcance por versión

| Versión | Endpoints a implementar |
|---|---|
| **0.1 (MVP)** | Workspace completo, Project (lectura) |
| **0.2** | Runtime completo (start/stop/status/services/logs vía WS) |
| **0.3** | Automation, Monitoring, Plugin |
| **1.0** | Resource Management, Environment diffing, autenticación JWT local y estabilización/documentación |
| **Posterior a 1.0** | Preferencia de editor por proyecto, abrir archivo y línea desde el catálogo de rutas, acción de automatización `OPEN_EDITOR` |

Con esto ya tienes el contrato completo antes de escribir un solo `@RestController`.
