# Siguiente nivel de DevVault

Alcance autorizado: experiencia inicial, diagnóstico integrado, entornos reutilizables,
confiabilidad y mantenibilidad. Se conserva el trabajo previo del repositorio.

## Entregas y aceptación

- [x] Seguridad: host local verificado y expresiones de reglas restringidas, con pruebas.
- [x] Ajustes utilizables: editor, frecuencia de métricas, detectores y diagnóstico de conexión.
- [x] Diagnóstico integrado: estado de servicios, requisitos y enlaces a evidencias/logs.
- [x] Métricas agregadas con resultados parciales y sin espera artificial por contenedor.
- [x] Primeros pasos conectados con registro, escaneo y ejecución de proyectos.
- [x] Entornos guardados con dependencias, historial, arranque y detención ordenados.
- [x] Automatización con notificaciones visibles y plantillas sin ejecución de código.
- [x] Pruebas de navegador con fixtures, contratos TypeScript/JSDoc y CI por plataforma.
- [x] Medición de escaneo y decisión documentada sobre caché incremental.
- [x] Evaluación inicial del almacenamiento integrado y soporte de PostgreSQL existente.

## Alcance que no se presenta como terminado

- La base embebida y la migración de datos requieren el prototipo descrito en
  `ADR-Almacenamiento-Local.md`; el motor soportado sigue siendo PostgreSQL.
- La caché incremental queda condicionada a medir workspaces reales y definir
  invalidación completa; ver `Rendimiento-Escaneo.md`.
- El frontend sigue en JavaScript. Los contratos nuevos no equivalen a una
  migración completa a TypeScript ni a un chequeo estático de toda la aplicación.
- La matriz Linux se ejecutará en CI al subir la rama. Esta sesión valida Windows
  y sintaxis de los lanzadores Bash, sin afirmar una ejecución completa en Linux.

## Verificación

Las pruebas Java incluyen PostgreSQL temporal, migración V12 y rechazo de
operaciones concurrentes sobre un proyecto. El CI de Linux rechaza resultados
con suites de integración omitidas.

La UI tiene seis recorridos de Chromium: preferencias persistentes, primeros
pasos, guardar un entorno, métricas parciales, diagnóstico de puertos y reconexión
de logs. Usan fixtures de API y no sustituyen el smoke test con procesos reales.

La ejecución Windows ampliada comprueba Node start/stop, un entorno guardado,
reinicio del backend, persistencia de su historial y eliminación del registro
sin borrar la carpeta del proyecto. La prueba pasó los siete pasos en Windows.
También pasaron 90 pruebas Java y seis recorridos de Chromium. Una prueba Java
estaba deshabilitada de forma explícita. El JAR y la UI compilaron correctamente.
La ejecución en Linux queda pendiente del CI.
