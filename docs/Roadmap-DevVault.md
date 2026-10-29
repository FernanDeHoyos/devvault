# Hoja de ruta funcional de DevVault

Este documento registra las mejoras priorizadas, su alcance, decisiones,
criterios de aceptación y estado de verificación. La plataforma objetivo actual
es Windows y Linux. macOS queda para una fase posterior con su propia revisión
de permisos y validación.

## Objetivo del producto

DevVault debe ayudar a pasar de descubrir un proyecto a ejecutarlo y depurarlo
localmente, manteniendo visibles sus procesos, puertos, logs y consumo. La
prioridad es que el ciclo de vida sea confiable y reproducible antes de añadir
integraciones de equipo o funciones de mayor alcance.

## Prioridades

| # | Mejora | Estado |
|---|---|---|
| 1 | Detener de forma confiable el árbol de procesos del proyecto | Implementado; falta smoke test en Windows y Linux |
| 2 | Esperar a que un entorno Docker Compose esté disponible | Implementado; falta smoke test con Docker |
| 3 | Inventario de puertos, procesos ocupantes y conflictos | Implementado; falta smoke test en Windows y Linux |
| 4 | Logs conjuntos, búsqueda, filtros y exportación | Implementado en UI; pendiente smoke test manual |
| 5 | Recetas de proyecto portables para servicios locales | Implementación inicial lista; falta validar en Windows y Linux |
| 6 | Health check HTTP configurable en recetas | Implementado; falta smoke test en Windows y Linux |

## 1. Ciclo de vida de procesos

### Comportamiento

- DevVault observa los procesos descendientes de cada comando local mientras el
  proyecto está activo.
- Al detener, termina también descendientes observados aunque el proceso raíz
  haya terminado primero.
- En Windows usa `taskkill /T` para el árbol y verifica los procesos observados;
  en Linux usa `ProcessHandle` para finalizar el árbol.
- Antes de aceptar otro arranque o eliminar el proyecto, comprueba que no quede
  vivo ningún proceso que DevVault haya observado.
- Compara la hora de inicio asociada al PID para reducir el riesgo de actuar
  sobre otro proceso si el sistema reutilizó el identificador.

### Criterios de aceptación

- Detener un proyecto Node libera el puerto del servidor y termina `npm`, shells
  y procesos de servidor que DevVault haya observado.
- El estado no pasa a `STOPPED` si queda vivo un descendiente observado.
- Los procesos ajenos al proyecto no se terminan.
- El mismo flujo funciona en Windows y Linux.

### Verificación registrada

- `compileJava` completó correctamente el 29 de octubre de 2026.
- No se ejecutó una prueba de sistema operativo; falta el smoke test indicado en
  [Proceso-Pruebas-Multiplataforma.md](Proceso-Pruebas-Multiplataforma.md).

## 2. Arranque de entornos Docker Compose

### Comportamiento

- Se reconocen `compose.yaml`, `compose.yml`, `docker-compose.yaml` y
  `docker-compose.yml`, en ese orden de preferencia.
- Docker Compose aplica el orden de `depends_on` definido por el proyecto.
- Tras `compose up`, DevVault espera a que cada contenedor esté corriendo. Si el
  contenedor declara `healthcheck`, espera a que Docker reporte `healthy`.
- El límite usa `devvault.runtime.startup-timeout-seconds` (por defecto, 300 s)
  como plazo total para el conjunto.
- Un stack sin contenedores o con un servicio que no quede listo se marca como
  fallido y conserva el estado observado para facilitar el diagnóstico.
- Los comandos externos de Compose tienen un límite de diez minutos y su salida
  se drena en paralelo para que no bloquee el proceso por llenar el buffer.
- Un servicio sin `healthcheck` se considera listo cuando está `running`; Compose
  no puede inferir disponibilidad de aplicación sin una señal de salud.

### Criterios de aceptación

- Los servicios declarados con dependencias arrancan conforme a Compose.
- Un servicio con `healthcheck` no se presenta como listo antes de `healthy`.
- Una dependencia fallida produce estado fallido con servicio y estado visibles.
- Los cuatro nombres de archivo Compose se reconocen al iniciar y detener.
- El flujo se valida en Windows y Linux con un stack que incluya healthchecks.

### Verificación registrada

- `compileJava` completó correctamente el 29 de octubre de 2026.
- No se ejecutó un stack Docker de prueba; falta el smoke test.

## 3. Inventario y conflictos de puertos

### Alcance

- La interfaz tendrá la ruta **Puertos** y mostrará cada puerto registrado por
  los servicios de DevVault.
- `GET /api/v1/ports` agrupa los servicios que reclaman el mismo puerto y añade
  los listeners TCP que reporta el sistema operativo, junto con PID y nombre del
  ejecutable cuando estén disponibles.
- Windows consulta `netstat -ano -p tcp`; Linux consulta `lsof` y usa `ss` como
  alternativa si `lsof` no está disponible.
- Cada puerto muestra proyectos/servicios que lo reclaman, estado del servicio,
  ocupación del sistema y una explicación cuando existe un conflicto.
- El enlace a `http://localhost:<puerto>` solo aparece cuando hay exactamente
  un servicio DevVault activo, el sistema reporta un listener y no hay conflicto.
- La vista es de solo lectura: identifica procesos externos, pero nunca los
  termina ni cambia su estado.

### Reglas de conflicto

- **Varios servicios activos declaran el mismo puerto:** conflicto.
- **Un proceso escucha, pero ningún servicio DevVault está activo en ese puerto:**
  conflicto; puede ser un proceso huérfano o una aplicación externa.
- **Un servicio aparece activo, pero el sistema no encuentra un listener:**
  conflicto o estado desactualizado.
- **Sin listener y sin servicios activos:** puerto disponible.
- **No se puede ejecutar el escaneo del sistema:** estado desconocido, sin
  afirmar que el puerto está libre.

### Criterios de aceptación

- El inventario agrupa por puerto y actualiza la información cada diez segundos.
- Un servicio detenido cuyo proceso siga escuchando se identifica como conflicto
  y se muestra el PID/ejecutable visible.
- Un proyecto activo con listener puede abrirse en el navegador desde la tabla.
- Si el escaneo no está disponible, la pantalla indica qué utilidad del sistema
  falta y no muestra falsamente los puertos como libres.
- El inventario se valida en Windows y Linux; Docker publicado y procesos locales
  quedan cubiertos por la matriz.

### Límites conocidos

- El inventario se construye a partir de puertos guardados en servicios
  detectados; todavía no es un explorador de todos los listeners del equipo.
- En Linux se necesita `lsof` o `ss`; en Windows, `netstat`. Los permisos del SO
  pueden impedir mostrar el nombre del ejecutable; el PID puede seguir visible.
- El control de puertos no intenta detener procesos externos automáticamente.

## 4. Logs conjuntos y herramientas de diagnóstico

### Comportamiento

- La vista **Logs** ofrece los servicios en estado `RUNNING` del proyecto
  seleccionado, o permite abrir uno de esos servicios individualmente.
- Al combinar servicios, la UI abre una conexión WebSocket por servicio y marca
  cada línea con su servicio de origen. Al cambiar proyecto o selección, cierra
  las conexiones anteriores.
- La búsqueda recorre mensaje, servicio y nivel. El filtro ofrece todos los
  niveles, errores, advertencias, información y debug.
- La exportación descarga las líneas visibles tras aplicar los filtros en un
  archivo `.log`.
- La vista conserva como máximo las últimas 2.000 líneas recibidas para limitar
  el uso de memoria. Los registros no se persisten.
- Si el runtime no informa el nivel, la UI intenta inferirlo por palabras del
  mensaje; por ello el nivel inferido es orientativo.

### Criterios de aceptación

- Elegir todos los servicios conecta solo los servicios activos y permite ver
  el estado de conexión de cada uno.
- Cambiar proyecto o servicio cierra las conexiones WebSocket anteriores.
- Buscar y filtrar actualiza las líneas visibles y el contador.
- Exportar genera un `.log` con solo las líneas que coinciden con los filtros.
- El flujo se valida manualmente con un proyecto que tenga varios servicios
  activos y mensajes de distintos niveles.

### Verificación registrada

- `vite build` terminó correctamente en las dos copias de la UI el 29 de
  octubre de 2026.
- Falta el smoke test manual de WebSocket y descarga del archivo.

## 5. Recetas portables de ejecución local

### Archivo y prioridad

- Un proyecto puede incluir `devvault.run.json` en su raíz para declarar cómo
  ejecutar localmente un servicio. El formato está versionado con `version: 1`.
- Si existe receta, prevalece sobre la detección automática de tecnología. Si
  no existe, los plugins mantienen el comportamiento actual.
- Docker Compose conserva prioridad sobre la receta porque define el ciclo de
  vida completo de un stack.
- La receta declara `service.name`, `service.command` como arreglo de argumentos,
  y admite `windowsCommand` para adaptar ejecutables o wrappers de Windows.
- También admite `workingDirectory` relativo a la raíz, `port`,
  `startupTimeoutSeconds` y variables de `environment` con valores de texto.
- No se interpola un shell. El comando se pasa directamente a `ProcessBuilder`;
  cada argumento debe ir separado en el arreglo.
- Por seguridad, la carpeta de trabajo debe existir dentro del proyecto y el
  timeout debe estar entre 5 y 1.800 segundos.

Ejemplo completo: [devvault.run.example.json](devvault.run.example.json).

### Límite de esta versión

- Esta receta inicia un proceso local por proyecto, acorde con el ciclo de vida
  local actual de DevVault. Para coordinar varios procesos locales se debe usar
  Docker Compose; la receta no intenta supervisar una lista de procesos.
- Los valores de entorno quedan en texto claro en el archivo del proyecto. No
  se deben incluir secretos en una receta que se vaya a compartir o versionar.

### Criterios de aceptación

- Una receta válida ejecuta el comando declarado, en su carpeta configurada y
  con las variables de entorno especificadas.
- Una receta admite comando específico de Windows y comando predeterminado para
  Linux; en ausencia de `windowsCommand` se usa `command` en ambos.
- Una receta inválida produce un error legible y no inicia un proceso.
- La detección automática sigue funcionando cuando no existe receta.
- El flujo se valida en Windows y Linux mediante el smoke test multiplataforma.

### Verificación registrada

- `compileJava` completó correctamente el 29 de octubre de 2026.
- No se ejecutó prueba automatizada ni smoke test en Windows o Linux.

## 6. Health checks HTTP configurables

### Comportamiento

- Una receta local puede declarar `service.healthCheckUrl` como endpoint HTTP
  local para readiness, por ejemplo `http://127.0.0.1:8080/health`.
- El URL debe apuntar a `localhost`, `127.0.0.1` o `::1`; no se hacen
  solicitudes a hosts remotos ni se siguen redirecciones.
- DevVault marca el servicio como iniciado cuando el endpoint devuelve un
  status HTTP 2xx. Una respuesta 3xx, 4xx, 5xx o una conexión fallida mantiene
  el arranque esperando hasta vencer el timeout de la receta.
- Si la receta no declara `healthCheckUrl`, se conserva el probe actual basado
  en puertos, salida del proceso y listeners del sistema operativo.
- El puerto del servicio se toma del puerto de la URL cuando está explícito;
  con URL sin puerto se usa el puerto HTTP 80.

### Criterios de aceptación

- Un endpoint local que devuelve 2xx permite marcar el servicio como `RUNNING`.
- Respuestas no 2xx no declaran el proyecto sano y el timeout deja una razón
  que incluye el endpoint y el último status observado.
- URLs remotas, HTTPS y URLs con credenciales se rechazan al leer la receta.
- Sin URL de salud, los proyectos mantienen el comportamiento actual.
- Se valida el flujo en Windows y Linux con una receta y un servidor HTTP local.

### Verificación registrada

- `compileJava` completó correctamente el 29 de octubre de 2026.
- No se ejecutó prueba automatizada ni smoke test multiplataforma.

## Registro de cambios

### 2026-10-29

- Se completaron los cambios de implementación de las prioridades 1 y 2.
- Se implementó la prioridad 3 con lectura de listeners TCP multiplataforma,
  API y vista de inventario; frontend y backend compilaron.
- Se añadió a Logs la combinación de servicios activos, búsqueda, filtro por
  nivel y exportación de las líneas visibles; ambas copias de UI compilaron.
  Falta el smoke test manual del flujo en vivo.
- Se añadió soporte inicial para `devvault.run.json`, con comandos por
  plataforma, carpeta de trabajo, puerto, timeout y entorno; `compileJava`
  completó. Falta validar el arranque y la parada en Windows y Linux.
- Se añadió `service.healthCheckUrl` para esperar HTTP 2xx en endpoints de
  loopback; `compileJava` completó. Falta validar el flujo en Windows y Linux.
- Se requiere reiniciar/reconstruir el backend integrado para cargar cambios y
  completar pruebas manuales en Windows y Linux antes de declarar soporte
  verificado.
