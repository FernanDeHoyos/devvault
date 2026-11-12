# Medición reproducible del escaneo

`ScannerBenchmarkTest` genera 20 proyectos Node con 1.000 archivos fuente y
directorios node_modules excluidos. Ejecuta tres escaneos y valida que no crezca
el número de proyectos. El reporte JUnit conserva `SCAN_BENCHMARK` con los tiempos.

Ejecutar `gradlew test --tests '*ScannerBenchmarkTest'` y consultar
`build/test-results/test/TEST-com.devvault.discovery.application.ScannerBenchmarkTest.xml`.
Los tiempos dependen del disco, antivirus, JVM y carga; no son un SLA ni un límite
de CI. La interfaz muestra además el tiempo real del último escaneo del workspace.

## Decisión sobre caché

Medición local registrada para esta entrega: 6.827 ms en la primera pasada,
645 ms en la segunda y 545 ms en la tercera. Las tres detectaron exactamente
20 proyectos. La diferencia incluye calentamiento de JVM y cachés del sistema;
no demuestra por sí sola el costo de ningún detector individual.

Se conserva el escaneo completo como fuente de verdad. Antes de añadir caché
incremental, medir workspaces reales y atribuir el costo por detector. Invalidar
solo package.json sería incorrecto: las evidencias también provienen de fuentes,
lockfiles, configuración, variables y módulos. Una caché futura necesita conocer
todos los archivos leídos y las altas/bajas de archivos; también debe invalidarse
si cambia el catálogo o la habilitación de detectores.

Esta entrega añade la medición y separa la lectura limitada de archivos de los
parsers de arquitectura. No introduce resultados de escaneo potencialmente obsoletos.
