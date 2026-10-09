# DevVault — Diseño de API REST (v1)

## Convenciones generales

- **Base path:** `/api/v1`
- **Formato:** JSON en request y response, `Content-Type: application/json`
- **Auth:** no hay. DevVault se ejecuta íntegro en la máquina del usuario y enlaza a `127.0.0.1` por defecto, así que la superficie expuesta es el propio equipo. Lo que sí se aplica es `SameOriginFilter`: cualquier petición con cabecera `Origin` que no sea un origen propio devuelve `403`. Eso impide que una página web cualquiera visitada desde el navegador pueda lanzar peticiones locales (ver *Alcance y seguridad*).
- **Configuración inicial:** ninguna. No hay administrador, ni contraseña, ni clave de recuperación.
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

## 0. Salud del servicio

### `GET /health`
Comprueba que DevVault responde **y que su base de datos está accesible**. No
pertenece a ningún módulo de negocio: lo usan los scripts de arranque
(`scripts/Iniciar-dev.ps1`, el empaquetado de preview) para decidir cuándo la
aplicación está lista, y una página que abra `bootRun` a ciegas y falle con
`Connection refused` sin decir por qué.

**Response `200`:** `{ "status": "UP", "database": "Operative", "timestamp": "..." }`
**Response `200`:** con la base caída, `status` y `database` valen `DOWN` y
`Unreachable` respectivamente.

> Devuelve `200` en ambos casos a propósito: el endpoint responde, y quien
> pregunta tiene que leer `status` para saber si el servicio está sano. Un `503`
> sería defendible, pero los scripts de arranque interpretarían un `503` como
> "aún levantándose" y esperarían a un `200` que ya había llegado.

---

## 1. Workspace

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
`status` ∈ `NOT_STARTED | IN_PROGRESS | COMPLETED | FAILED`

> Un Workspace que nunca se ha escaneado devuelve `NOT_STARTED` con 200, no 404.
> El estado vive en memoria, así que solo existe si alguien lanzó un escaneo;
> devolver 404 llenaba la consola de la UI en el caso normal de "todavía no he
> escaneado esto", sin que hubiera nada que arreglar.

### `DELETE /workspaces/{id}`
Quita un Workspace de DevVault. Se detienen antes sus runtimes y se borran en
cascada sus proyectos y todos los datos que cuelgan de ellos (profiles,
services, containers, runtime_instances, alerts, automation_rules,
git_fetch_events). **Los archivos de la carpeta no se tocan:** borrar un
Workspace es olvidarse de una carpeta, no borrarla del disco.

**Response:** `204` · **Errores:** `404` si el Workspace no existe

> La cascada la pone el esquema (`projects.workspace_id` es `ON DELETE CASCADE`),
> así que esto no necesita ninguna migración. Detener los runtimes antes sí es
> necesario: la cascada borra filas, pero no sabe nada de los contenedores de
> Docker ni de los procesos locales, que quedarían sin control. Si un runtime se
> resiste a pararse, el borrado aborta y el Workspace queda intacto: es
> preferible a perder el control de un proceso huérfano.

---

## 2. Project

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

## 3. Runtime

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
Azúcar sintáctico sobre stop + start. **No implementado** *(v1.0)*.

> No existe en el código: cuando el usuario reinicia, la UI encadena
> `POST /stop` seguido de `POST /start`. Está en el contrato porque es una
> operación que la interfaz necesita, pero no merece un endpoint propio: dos
> peticiones ya dan el resultado y cada una tiene su respuesta real, en lugar de
> un 202 que obligaría a consultar el estado después.

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

### `GET /runtime/active`
Vista agregada de todo lo que está corriendo ahora, sin importar a qué proyecto
pertenezca.

**Response `200`:** array de:
```json
{ "projectId": "uuid", "overallStatus": "RUNNING", "startedAt": "...", "services": [
  { "name": "app-backend", "status": "RUNNING", "kind": "LOCAL_PROCESS", "pid": 22976 }
]}
```

> Existe para el Dashboard: sin ella, la UI tendría que pedir
> `GET /projects/{id}/status` por cada proyecto (N+1) solo para pintar una lista
> de los que están activos. No estaba en el contrato original, aunque el código
> lo implementaba desde antes que esta tabla.

### `WS /projects/{id}/logs?service={serviceName}&ticket={ticket}`
Canal WebSocket de streaming de logs en vivo (CU-07). El handshake lo valida `SameOriginFilter` cuando el navegador manda cabecera `Origin`.
**Mensajes emitidos (servidor → cliente):**
```json
{ "timestamp": "...", "level": "INFO", "message": "Started BarberApplication in 2.1s" }
```
**Cierre del canal:** si el contenedor se cae, el servidor envía un mensaje de cierre con el motivo antes de cerrar el socket.

---

## 4. Resource

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

## 5. Environment

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

## 6. Automation

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

## 7. Monitoring

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

## 8. Plugin System

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

## 9. Editor de código

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
| POST | `/workspaces` | Workspace | CU-03 |
| GET | `/workspaces` | Workspace | — |
| GET | `/workspaces/directories?path=` | Workspace | — |
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
| POST | `/projects/{id}/restart` | Runtime *(v1.0)* | CU-05/06 |
| GET | `/projects/{id}/status` | Runtime | CU-05 |
| GET | `/projects/{id}/services` | Runtime | CU-07 |
| GET | `/runtime/active` | Runtime | CU-04 |
| WS | `/projects/{id}/logs` | Runtime | CU-07 |
| POST | `/resources` | Resource *(v1.0)* | CU-08 |
| GET | `/resources` | Resource *(v1.0)* | — |
| GET | `/resources/{id}` | Resource *(v1.0)* | — |
| DELETE | `/resources/{id}` | Resource *(v1.0)* | — |
| POST | `/projects/{id}/resources/{resourceId}` | Resource *(v1.0)* | CU-09 |
| DELETE | `/projects/{id}/resources/{resourceId}` | Resource *(v1.0)* | — |
| GET | `/projects/{id}/environment` | Environment *(v1.0)* | CU-10 |
| GET | `/projects/{id}/environment/diff` | Environment *(v1.0)* | CU-10 |
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
| GET | `/health` | Salud | — |

**Total: 41 endpoints** en el contrato. **32 están implementados** (31 REST + 1 WebSocket) y 9 están diseñados pero pendientes de v1.0: Resource (6), Environment (2) y `POST /projects/{id}/restart`, marcados con *(v1.0)*.

> Las cifras están comprobadas contra el código, no estimadas: 38 secciones de
> endpoint + WebSocket + `GET /health` + `GET /runtime/active`. Los dos últimos
> los implementaba el código desde antes de que esta tabla los recogiera, y
> `restart` figuraba aquí sin existir en ningún sitio: la UI reinicia encadenando
> `POST /stop` y `POST /start`.

No hay autenticación: el acceso se protege enlazando a `127.0.0.1` y rechazando peticiones con un `Origin` ajeno.

---

## 11. Alcance por versión

| Versión | Endpoints a implementar |
|---|---|
| **0.1 (MVP)** | Workspace completo, Project (lectura) |
| **0.2** | Runtime completo (start/stop/status/services/logs vía WS) |
| **0.3** | Automation, Monitoring, Plugin |
| **1.0** | Resource Management, Environment diffing y estabilización/documentación |
| **Posterior a 1.0** | Preferencia de editor por proyecto, abrir archivo y línea desde el catálogo de rutas, acción de automatización `OPEN_EDITOR` |

Con esto ya tienes el contrato completo antes de escribir un solo `@RestController`.
