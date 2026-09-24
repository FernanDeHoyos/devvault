# DevVault Backend

Backend de **DevVault**, una aplicación local para descubrir proyectos de desarrollo, iniciarlos, observar su estado y consultar información de su repositorio Git.

## Funcionalidades

- Registro de workspaces y exploración de proyectos en sus directorios.
- Detección de proyectos Spring Boot (Maven/Gradle), Node.js/React y PHP/Laravel.
- Catálogo estático de rutas para Spring MVC, Laravel, Express, Fastify y NestJS.
- Inicio y detención de proyectos locales o definidos con Docker Compose.
- Lectura de logs en tiempo real mediante WebSocket.
- Métricas y alertas de runtime, reglas de automatización y administración de plugins integrados.
- Resumen Git con ramas, commits, estado de cambios, relación con upstream y `fetch` manual.

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

La API queda disponible en `http://localhost:8080/api/v1`. Comprueba el estado del servicio y la base de datos en:

```text
GET http://localhost:8080/api/v1/health
```

Compose usa PostgreSQL 16 en el puerto `5432`, base de datos `devvault` y credenciales locales de desarrollo definidas en `docker-compose.yml`. La configuración de conexión de Spring está en `src/main/resources/application.yml`. Cambia esas credenciales antes de usar una base de datos accesible desde otras máquinas.

Flyway aplica las migraciones al arrancar; Hibernate valida el esquema existente y no lo genera.

## API principal

Base path: `/api/v1`.

| Área | Rutas principales |
| --- | --- |
| Workspaces | `/workspaces`, `/workspaces/{id}/scan` |
| Proyectos | `/projects`, `/projects/{id}`, `/projects/{id}/routes` |
| Runtime | `/projects/{id}/start`, `/stop`, `/status`, `/services` |
| Logs | WebSocket `/projects/{id}/logs?service={serviceName}` |
| Git | `/projects/{id}/git`, `/projects/{id}/git/fetch` |
| Monitoring | `/projects/{id}/metrics`, `/alerts` |
| Automation | `/automation/rules` |
| Plugins integrados | `/plugins` |

## Pruebas

```bash
# Windows
.\gradlew.bat test

# Linux / macOS
./gradlew test
```

Las pruebas de integración basadas en Testcontainers pueden requerir que Docker esté disponible.

## Estructura

El código se organiza por módulos funcionales bajo `src/main/java/com/devvault`: `workspace`, `discovery`, `runtime`, `monitoring`, `automation`, `plugin` y `shared`. Las migraciones SQL están en `src/main/resources/db/migration`.

## Alcance y seguridad

DevVault está pensado para ejecutarse localmente. La API todavía no requiere autenticación y la configuración de CORS de desarrollo permite el frontend local en `http://localhost:5050`. No expongas el backend o la base de datos a redes no confiables con la configuración de desarrollo.
