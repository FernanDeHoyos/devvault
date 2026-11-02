# Especificación: escaneo, stack y mapa del proyecto

> Historias de origen: [DevVault Project Intelligence](./DevVault-Project-Intelligence.md)
> Estado: En implementación
> Alcance: primera entrega de Project Intelligence

## Scope Baseline

- **Método de descubrimiento**: historias del documento adjunto y revisión de las pantallas, el escáner y los perfiles actuales de DevVault.
- **Historias identificadas**: 29 (HU-01 a HU-29).
- **Historias incluidas**: HU-01 a HU-06, HU-11 a HU-16 y una primera lectura arquitectónica acotada de módulos, dependencias, bases de datos, herramientas y servicios declarados (HU-21 y HU-22).
- **Historias fuera de esta entrega**: clasificación detallada de todos los archivos, búsqueda/filtros avanzados de dependencias, requisitos del entorno, historial de escaneos y recomendaciones. Se conservan en el documento de origen para etapas posteriores.
- **Objetivo de experiencia**: al abrir un proyecto, mostrar primero su estado de escaneo y stack detectado; el mapa debe ser una sección principal y visible, no una pantalla secundaria difícil de encontrar.

## User Scenarios & Testing

### User Story 1 — Escanear y actualizar el perfil técnico (Priority: P1)

Como desarrollador, quiero escanear o volver a escanear un workspace, para que DevVault descubra mis proyectos y mantenga actualizado el perfil técnico de cada uno.

**Historias de origen**: HU-01, HU-04, HU-06.

**Why this priority**: el escaneo produce la información necesaria para todas las vistas de inteligencia del proyecto.

**Independent Test**: registrar una carpeta de prueba, escanearla y comprobar el estado, fecha y resultado de los proyectos encontrados, sin abrir el código fuente en un editor.

**Acceptance Scenarios**:

1. **Given** un workspace accesible con proyectos reconocibles, **When** el usuario inicia un escaneo, **Then** DevVault muestra actividad y al terminar informa el resultado y la fecha del escaneo.
2. **Given** un workspace ya escaneado, **When** el usuario lo vuelve a escanear, **Then** los perfiles existentes se actualizan sin duplicar proyectos.
3. **Given** un escaneo en curso, **When** el usuario intenta iniciar otro para el mismo workspace, **Then** DevVault informa que ya existe uno activo y no inicia un duplicado.
4. **Given** que un escaneo falla, **When** el usuario consulta el proyecto, **Then** ve una causa comprensible y conserva el último perfil exitoso.
5. **Given** directorios excluidos como `.git`, `node_modules`, `build`, `dist` o `.gradle`, **When** se analiza el workspace, **Then** estos no se recorren como contenido del proyecto.

### User Story 2 — Ver el stack tecnológico con evidencia (Priority: P1)

Como desarrollador, quiero ver los lenguajes, frameworks y versiones detectados con sus fuentes, para entender y verificar rápidamente el stack de un proyecto.

**Historias de origen**: HU-02, HU-03, HU-05, HU-11, HU-12, HU-13.

**Why this priority**: es el beneficio más inmediato del escaneo y debe poder consultarse sin interpretar marcadores técnicos manualmente.

**Independent Test**: escanear proyectos de muestra con manifiestos conocidos y comprobar que el resumen muestra las tecnologías respaldadas por esos archivos, con enlaces a su evidencia.

**Acceptance Scenarios**:

1. **Given** un proyecto escaneado, **When** el usuario abre su detalle, **Then** ve lenguajes y tecnologías agrupados por responsabilidad, con indicación de tecnología principal o secundaria cuando haya evidencia suficiente.
2. **Given** una tecnología detectada, **When** el usuario consulta su evidencia, **Then** ve la ruta del archivo y, si está disponible, la dependencia, propiedad o marcador que la respalda.
3. **Given** que una versión aparece en un manifiesto, **When** se muestra la tecnología, **Then** DevVault muestra esa versión y su fuente; si no puede determinarla, lo indica en vez de inventarla.
4. **Given** que una tecnología solo puede inferirse y no confirmarse, **When** se muestra en el stack, **Then** queda identificada claramente como inferida.
5. **Given** evidencia insuficiente, **When** el escaneo termina, **Then** DevVault no presenta la tecnología como detección confirmada.
6. **Given** que la lista contiene varias tecnologías, **When** el usuario filtra por categoría, **Then** puede aislar frontend, backend, base de datos, infraestructura o herramientas sin volver a escanear.

### User Story 3 — Entender el proyecto mediante un mapa visible (Priority: P1)

Como desarrollador, quiero ver un mapa visual de los componentes y relaciones respaldados por el escaneo, para entender cómo se organiza el proyecto de un vistazo.

**Historias de origen**: HU-04, HU-05, HU-14, HU-15, HU-16.

**Why this priority**: el mapa da visibilidad al resultado del escaneo y diferencia Project Intelligence de un inventario de archivos.

**Independent Test**: abrir un proyecto de muestra con componentes y servicios declarados, comprobar que el mapa es accesible desde la vista principal y verificar que cada relación expone su evidencia.

**Acceptance Scenarios**:

1. **Given** un proyecto con componentes o servicios identificables, **When** el usuario abre su detalle, **Then** encuentra el mapa en la zona principal de la página, junto al resumen del stack.
2. **Given** que el mapa contiene componentes, **When** el usuario selecciona uno, **Then** ve su nombre, tipo y evidencia de origen.
3. **Given** que hay relaciones directas o inferidas, **When** se muestran, **Then** DevVault diferencia su nivel de evidencia y nunca inventa conexiones sin respaldo.
4. **Given** un mapa con varios tipos de componentes, **When** el usuario aplica filtros combinables, **Then** la vista se actualiza sin ejecutar otro escaneo.
5. **Given** un proyecto sin evidencia suficiente para construir relaciones, **When** se abre el mapa, **Then** DevVault muestra los componentes disponibles y comunica que no puede confirmar relaciones.

### User Story 4 — Consultar la composición arquitectónica declarada (Priority: P1)

Como desarrollador, quiero ver los módulos, dependencias, bases de datos, herramientas y servicios declarados, para entender de qué se compone el proyecto y qué archivos respaldan cada dato.

**Historias de origen**: HU-14, HU-21 y HU-22.

**Independent Test**: escanear proyectos de muestra Maven/Gradle, npm, Composer y Python, junto con configuraciones y un Compose de prueba; comprobar módulos, dependencias y bases de datos sin revelar valores de configuración.

**Acceptance Scenarios**:

1. **Given** un proyecto con módulos declarados o manifiestos en subdirectorios, **When** se consulta la arquitectura, **Then** DevVault muestra sus rutas y el manifiesto que las identifica.
2. **Given** manifiestos de dependencias soportados, **When** se consulta el inventario, **Then** DevVault muestra nombre, versión disponible, gestor, ámbito y evidencia; las dependencias transitivas solo se muestran cuando existe un lockfile interpretable.
3. **Given** un driver, una URL/configuración de base de datos o un servicio Compose, **When** se consulta el inventario, **Then** DevVault distingue driver declarado, configuración declarada y servicio declarado, sin afirmar conectividad o ejecución.
4. **Given** archivos con credenciales, **When** se analiza la configuración, **Then** la respuesta conserva únicamente la tecnología y el nombre de la propiedad, nunca la URL ni el secreto.

### Edge Cases

- La carpeta del proyecto desaparece o deja de ser accesible después de registrarla.
- Hay varios manifiestos o tecnologías en la raíz y en subdirectorios.
- Un manifiesto está mal formado o no puede leerse.
- Un escaneo parcial falla después de un escaneo exitoso.
- Una tecnología se declara como dependencia, pero no hay evidencia de uso efectivo en el código.
- El proyecto no tiene servicios o relaciones detectables.
- Un archivo de configuración contiene nombres o valores que podrían ser secretos.

## Requirements

### Functional Requirements

- **REQ-001**: DevVault DEBE permitir iniciar y repetir el escaneo del workspace que contiene el proyecto y mostrar si está pendiente, en curso, completado o fallido.
- **REQ-002**: DevVault DEBE registrar el momento y resultado del escaneo; un fallo posterior DEBE conservar el último resultado exitoso disponible.
- **REQ-003**: El escaneo DEBE ser de solo lectura y DEBE excluir directorios de generación, dependencias y control de versiones, incluyendo `.git`, `node_modules`, `build`, `dist` y `.gradle`.
- **REQ-004**: El escaneo DEBE identificar, como mínimo, Java, JavaScript, TypeScript, PHP, Python, Kotlin, HTML, CSS y SQL mediante evidencias de archivos o manifiestos.
- **REQ-005**: DevVault DEBE detectar tecnologías y frameworks soportados por su catálogo, incluyendo Spring Boot, React, Vue, Angular, Laravel, Node.js, Express y Django. Toda detección debe incluir evidencia; el producto no debe afirmar que reconoce tecnologías fuera del catálogo activo.
- **REQ-006**: El perfil técnico DEBE distinguir evidencia directa de inferencia y DEBE dejar sin confirmar cualquier tecnología cuya evidencia sea insuficiente.
- **REQ-007**: El stack DEBE mostrar el nombre de la tecnología, su categoría, su importancia relativa cuando pueda determinarse y la versión solo cuando haya una fuente verificable.
- **REQ-008**: Cada tecnología visible DEBE enlazar a la ruta de al menos una evidencia; cuando exista, también mostrará la propiedad, dependencia o marcador asociado.
- **REQ-009**: La vista de detalle DEBE presentar el resumen del escaneo y del stack antes de las secciones secundarias; el mapa aparecerá inmediatamente después de ese resumen, como contenido principal y sin quedar oculto en una pestaña secundaria.
- **REQ-010**: El mapa DEBE representar componentes identificados y relaciones sustentadas por evidencia; cada nodo y relación consultable mostrará su tipo y origen.
- **REQ-011**: El usuario DEBE poder seleccionar nodos y filtrar el mapa por categoría de componente, combinando filtros sin volver a escanear.
- **REQ-012**: Cuando no haya relaciones confirmables, DevVault DEBE mostrar el estado vacío con una explicación y no generar conexiones especulativas.
- **REQ-013**: Un manifiesto ilegible o un elemento no analizable DEBE aparecer como advertencia del escaneo sin detener el backend ni invalidar resultados exitosos de otros elementos.
- **REQ-014**: DevVault DEBE evitar guardar o exponer valores de contraseñas, tokens, claves y secretos encontrados al analizar archivos de configuración.
- **REQ-015**: DevVault DEBE actualizar los perfiles existentes al volver a escanear, sin duplicar proyectos con la misma identidad y ruta.
- **REQ-016**: DevVault DEBE leer dependencias de los manifiestos soportados y mostrar gestor, archivo, ámbito y versión solo cuando esté disponible; las dependencias transitivas solo se mostrarán si un lockfile compatible las identifica.
- **REQ-017**: DevVault DEBE identificar módulos declarados por los sistemas de construcción/workspaces y áreas de código reconocibles, manteniendo evidencia de su ruta de origen.
- **REQ-018**: DevVault DEBE distinguir una base configurada, un driver declarado y un servicio de base de datos declarado; estos estados NO DEBEN presentarse como una conexión validada o un servicio en ejecución.
- **REQ-019**: DevVault DEBE identificar herramientas de construcción/gestión y servicios Compose respaldados por sus archivos de declaración.
- **REQ-020**: El inventario arquitectónico DEBE omitir valores de configuración, URLs y credenciales; solo puede mostrar el nombre de la propiedad y el tipo de tecnología identificado.

### Key Entities

- **Scan**: ejecución de análisis con estado, fecha de inicio/fin, resultado y advertencias.
- **Project profile**: resumen técnico más reciente que puede consultarse para un proyecto.
- **Technology**: lenguaje, framework o herramienta detectada, con categoría, versión opcional y estado de certeza.
- **Evidence**: archivo y marcador que justifican una detección.
- **Component**: módulo, servicio u otro elemento representable en el mapa.
- **Relationship**: conexión entre componentes, con tipo y nivel de evidencia.
- **Scan warning**: elemento no analizado o condición que limita el resultado.
- **Dependency**: paquete declarado o resuelto, con gestor, ámbito, versión disponible y evidencia de origen.

## Success Criteria

### Measurable Outcomes

- **SC-001**: En un workspace de prueba con hasta 50 proyectos reconocibles, el escaneo termina en menos de 10 segundos en el entorno de referencia del proyecto.
- **SC-002**: El 100 % de las tecnologías presentadas como confirmadas muestra al menos una fuente de evidencia navegable.
- **SC-003**: En pruebas de aceptación, el usuario puede localizar el stack y abrir el mapa desde el detalle de un proyecto en no más de dos acciones.
- **SC-004**: En el conjunto de pruebas con marcadores conocidos, DevVault identifica correctamente los lenguajes y frameworks presentes y no confirma los ausentes.
- **SC-005**: Ninguna prueba de escaneo modifica archivos del proyecto ni conserva valores de secretos de los archivos analizados.
- **SC-006**: Un fallo de escaneo no detiene la aplicación y no elimina el último resultado exitoso.
- **SC-007**: Las pruebas de manifiestos Maven/Gradle, npm, Composer y Python identifican sus dependencias y módulos esperados sin atribuir dependencias transitivas si no hay lockfile compatible.
- **SC-008**: Las pruebas de base de datos distinguen configuración, driver y servicio declarado, y confirman que ningún valor secreto se devuelve en el perfil o el mapa.

## Assumptions

- El escaneo se inicia desde el workspace existente; no se añade en esta primera entrega un flujo separado para registrar proyectos individuales.
- Los detectores pueden ampliar su catálogo gradualmente. Una tecnología sin detector o evidencia disponible se informa como no identificada, no como soportada.
- La primera versión del mapa prioriza módulos, tecnologías y servicios declarados que puedan sustentarse en archivos; no promete un grafo completo de llamadas o dependencias transitivas.
- El diseño favorece la visibilidad en escritorio; la interfaz también debe seguir siendo utilizable en pantallas pequeñas.
- Esta entrega no incluye edición automática, ejecución de archivos ni exportación del mapa.
