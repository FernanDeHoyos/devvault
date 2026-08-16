# DevVault — Diseño de API REST (v1)

## Convenciones generales

- **Base path:** `/api/v1`
- **Formato:** JSON en request y response, `Content-Type: application/json`
- **Auth (cuando se active, ver HU-01):** `Authorization: Bearer {jwt}`
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

### `POST /auth/login`
Autentica al usuario y devuelve un JWT.

**Request:**
```json
{ "email": "dev@local.com", "password": "••••••••" }
```
**Response `200`:**
```json
{ "token": "eyJhbGciOi...", "expiresAt": "2026-08-13T22:30:00Z" }
```
**Errores:** `401` credenciales inválidas (mensaje genérico, sin revelar si el email existe — regla de negocio de Identity)

### `POST /auth/logout`
Invalida la sesión actual (si se implementa blacklist de tokens).
**Response:** `204 No Content`

### `GET /auth/me`
Devuelve el perfil del usuario autenticado.
**Response `200`:**
```json
{ "id": "uuid", "email": "dev@local.com", "role": "USER", "createdAt": "..." }
```
**Errores:** `401` sin token o token inválido

---

## 2. Workspace

### `POST /workspaces`
Crea un Workspace apuntando a una ruta local (HU-02).

**Request:**
```json
{ "name": "Mi PC — Development", "path": "D:/Development" }
```
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
Lista los `Service` + `Container` del proyecto, con detalle completo.
**Response `200`:** array de:
```json
{ "id": "uuid", "name": "postgres", "type": "DATABASE", "port": 5432, "status": "RUNNING", "containerId": "docker-abc123" }
```

### `WS /projects/{id}/logs?service={serviceId}`
Canal WebSocket de streaming de logs en vivo (CU-07).
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
  "name": "Notificar caída de contenedor",
  "projectId": "uuid",
  "trigger": { "eventType": "ContainerFailedEvent" },
  "conditions": [{ "expression": "service.type == 'DATABASE'" }],
  "actions": [{ "actionType": "NOTIFY", "params": { "channel": "desktop" } }]
}
```
**Response `201`:** la regla creada con su `id`
**Errores:** `400` si no se envía al menos un `trigger` (invariante de negocio)

### `GET /automation/rules`
Lista reglas, filtrable por `projectId` y `enabled`.

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
Métricas recientes de CPU/RAM por contenedor (CU-13).
**Query params:** `since` (default: última hora), `containerId` (opcional)
**Response `200`:**
```json
[{ "containerId": "uuid", "cpuPercent": 12.4, "memMb": 340, "capturedAt": "..." }]
```

### `GET /alerts`
Alertas activas o resueltas.
**Query params:** `status` (`ACTIVE|RESOLVED`), `projectId`
**Response `200`:** array de `Alert`

### `PATCH /alerts/{id}`
Marca una alerta como resuelta manualmente.
**Request:** `{ "resolved": true }`
**Response `200`:** alerta actualizada con `resolvedAt`

---

## 9. Plugin System

### `GET /plugins`
Lista los `PluginDescriptor` registrados (detectores de tecnología).
**Response `200`:**
```json
[{ "id": "uuid", "name": "spring-boot-detector", "targetMarkerFiles": "pom.xml", "version": "1.0", "enabled": true }]
```

### `PATCH /plugins/{id}`
Habilita/deshabilita un plugin de detección.
**Request:** `{ "enabled": false }`

---

## 10. Resumen de endpoints (referencia rápida)

| Método | Ruta | Módulo | Caso de uso |
|---|---|---|---|
| POST | `/auth/login` | Identity | CU-01 |
| POST | `/auth/logout` | Identity | CU-02 |
| GET | `/auth/me` | Identity | — |
| POST | `/workspaces` | Workspace | CU-03 |
| GET | `/workspaces` | Workspace | — |
| GET | `/workspaces/{id}` | Workspace | — |
| POST | `/workspaces/{id}/scan` | Workspace | CU-03 |
| GET | `/workspaces/{id}/scan/status` | Workspace | CU-03 |
| DELETE | `/workspaces/{id}` | Workspace | — |
| GET | `/projects` | Discovery | CU-04 |
| GET | `/projects/{id}` | Discovery | CU-04 |
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

**Total: 33 endpoints** (32 REST + 1 WebSocket) que cubren el 100% de las HU del MVP 0.1 y dejan la estructura lista para 0.2 y 0.3 sin rediseñar nada.

---

## 11. Alcance por versión

| Versión | Endpoints a implementar |
|---|---|
| **0.1 (MVP)** | Workspace completo, Project (lectura), Auth simplificado (`permitAll`, sin login real) |
| **0.2** | Runtime completo (start/stop/status/services/logs vía WS) |
| **0.3** | Resource, Environment, Automation, Monitoring, Plugin |

Con esto ya tienes el contrato completo antes de escribir un solo `@RestController`.
