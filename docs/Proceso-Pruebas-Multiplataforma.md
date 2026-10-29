# Proceso de pruebas multiplataforma

Este proceso valida DevVault en Windows y Linux. macOS queda fuera de la matriz
de soporte por ahora; sus particularidades de permisos, firma y ejecucion de
binarios requieren una validacion propia antes de ofrecer soporte.

## Metodologia

Se prueba de menor a mayor costo y se conserva evidencia por sistema operativo.
Un cambio no se declara listo por compilar solamente: debe pasar las capas que
afecte y el smoke test en una instancia limpia.

1. **Inventario y alcance:** identificar cambios, componentes, dependencias,
   comandos externos, rutas, permisos y servicios necesarios.
2. **Linea base:** registrar commit, versiones de Java/Node/npm/Docker, estado de
   PostgreSQL y resultados actuales antes de corregir.
3. **Validacion rapida:** lint y build del frontend; compilacion, pruebas
   unitarias y analisis estatico disponibles del backend.
4. **Pruebas de integracion:** API con PostgreSQL, migraciones Flyway y
   operaciones de workspace; usar Testcontainers cuando Docker este disponible.
5. **Pruebas de plataforma:** ejecutar la misma matriz en Windows y Linux,
   prestando atencion a invocacion de procesos, scripts, separadores/rutas,
   permisos de ejecucion y descubrimiento de puertos.
6. **Smoke test extremo a extremo:** iniciar la base y backend, comprobar salud,
   servir la UI integrada, descubrir un proyecto Node de prueba y comprobar su
   arranque/detencion; probar una operacion de workspace y reiniciar el backend
   para comprobar persistencia. En Linux,
   `scripts/smoke-test-linux.sh` automatiza esta secuencia contra el JAR y crea
   un contenedor PostgreSQL temporal aislado. En Windows, ejecutar
   `scripts/smoke-test-windows.ps1` hace la misma secuencia y crea otro
   contenedor aislado. Ambos scripts aceptan `DEVVAULT_DB_URL` junto con sus
   credenciales para usar una base ya preparada; en ese modo no la administran.
7. **Regresion y reporte:** repetir las capas afectadas y anotar comando,
   plataforma, resultado, omisiones y evidencia. Los fallos se clasifican como
   defecto, dependencia ausente, infraestructura o prueba inestable.

## Matriz de comprobacion

| Capa | Windows | Linux | Condicion de aceptacion |
| --- | --- | --- | --- |
| Frontend | `npm ci`, `npm run lint`, `npm run build` | mismos comandos | lint sin errores y build genera `dist/index.html` y assets |
| Backend | `gradlew.bat test` | `./gradlew test` | suite termina correctamente; IT con PostgreSQL corre donde Docker este disponible |
| Arranque local | `scripts/Iniciar-dev.ps1` | Compose + `./gradlew bootRun`; Vite separado en desarrollo | API sana y UI accesible por la URL documentada |
| Procesos gestionados | Node, Maven, Gradle y editores | Node, Maven, Gradle y editores disponibles en Linux | iniciar, detectar puerto, leer logs y detener sin dejar procesos huérfanos |
| Inventario de puertos | `netstat -ano -p tcp` | `lsof` o fallback `ss` | lista listeners para puertos registrados, reporta PID cuando está disponible y detecta ocupantes/conflictos |
| Persistencia | PostgreSQL 16 via Docker | PostgreSQL 16 via Docker | migraciones aplicadas y datos sobreviven al reinicio |
| Paquete integrado | JAR/preview de Windows | JAR autocontenido | portada, assets, rutas SPA y API salen del mismo origen |

## Smoke test minimo

1. `docker compose up -d postgres` y esperar `pg_isready`.
2. Construir/arrancar el backend por el camino documentado para el SO.
3. Confirmar `GET /api/v1/health` con `status=UP` y `database=Operative`.
4. Confirmar `GET /` devuelve la UI (HTTP 200), cargar JS/CSS y abrir una ruta
   interna de React directamente (por ejemplo `/workspaces`).
5. Escanear el workspace y arrancar/detener un proyecto Node temporal.
6. Crear, leer y eliminar un workspace; verificar que la carpeta original se
   conserva, reiniciar el backend y comprobar la persistencia.
7. Detener los procesos y confirmar que los puertos y procesos del test quedan
   libres.

## Evidencia por corrida

Guardar en el PR o registro de cambio: commit probado; OS/arquitectura; versiones
de Java, Node, npm y Docker; comandos exactos; resultado por capa; logs de
fallos; y pruebas omitidas con motivo. No registrar secretos ni contenido de
workspaces personales.

## Limites actuales

- `scripts/Iniciar-dev.ps1` y `scripts/Detener-dev.ps1` son especificos de
  Windows; Linux usa Gradle y Docker Compose.
- En desarrollo, `bootRun` ejecuta solo la API; Vite se ejecuta aparte. La UI
  integrada requiere el JAR construido con `previewUiDir`.
- `DevvaultApplicationTests` y `WorkspaceControllerIT` usan PostgreSQL con
  Testcontainers y se omiten si Docker no esta disponible. Una corrida sin
  daemon no demuestra que la integracion con PostgreSQL funciona.
- `DockerConnectionSpike` es un diagnostico manual que enumera contenedores del
  usuario; queda excluido de la regresion automatica.
- No se afirmara compatibilidad de macOS hasta completar una matriz y revisar
  controles de seguridad propios de esa plataforma.
