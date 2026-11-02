# Épica: DevVault Project Intelligence
Objetivo: permitir que un desarrollador explore, comprenda y diagnostique un proyecto de software mediante un mapa visual de su estructura, stack tecnológico, configuraciones, dependencias y servicios.

## Épica 1. Escaneo inteligente del proyecto
Esta épica constituye la base de toda la funcionalidad. El backend debe analizar el proyecto y generar un modelo estructurado que pueda ser utilizado por las distintas vistas del frontend.

### HU-01 — Escanear un proyecto
Como desarrollador,
quiero escanear un proyecto registrado en DevVault,
para obtener una representación técnica de su estructura y configuración.

Criterios de aceptación:

- Dado un proyecto registrado, cuando solicite un escaneo, DevVault debe analizar su directorio raíz.
- El sistema debe identificar archivos, directorios y configuraciones relevantes.
- El escaneo debe ignorar directorios comunes como `.git`, `node_modules`, `build`, `dist` y `.gradle`.
- El sistema debe registrar la fecha y el resultado del escaneo.
- Si ocurre un error, debe informar la causa sin detener el backend.
- El escaneo debe ser de solo lectura: no debe modificar archivos del proyecto.
Prioridad: P0 — MVP.

### HU-02 — Detectar lenguajes de programación
Como desarrollador,
quiero conocer los lenguajes utilizados en un proyecto,
para comprender rápidamente su composición tecnológica.

Criterios de aceptación:

- DevVault debe detectar lenguajes mediante archivos fuente y archivos de configuración.
- Debe identificar, como mínimo, Java, JavaScript, TypeScript, PHP, Python, Kotlin, HTML, CSS y SQL.
- Debe mostrar el lenguaje, su evidencia y su ubicación.
- Debe distinguir entre un lenguaje principal y uno secundario cuando exista evidencia suficiente.
- No debe considerar un lenguaje como principal únicamente por encontrar un archivo aislado.
Prioridad: P0 — MVP.

### HU-03 — Detectar frameworks y tecnologías
Como desarrollador,
quiero conocer los frameworks y tecnologías utilizados por mi proyecto,
para entender su stack tecnológico.

Criterios de aceptación:

- DevVault debe detectar tecnologías a partir de archivos de configuración y dependencias.
- Debe soportar inicialmente Spring Boot, React, Vue, Angular, Laravel, Node.js, Express, Django y otros frameworks definidos en el catálogo.
- Debe identificar la versión cuando esté disponible.
- Cada detección debe incluir la evidencia que la respalda.
- El sistema debe diferenciar entre una tecnología detectada y una inferida.
Prioridad: P0 — MVP.

### HU-04 — Generar el modelo técnico del proyecto
Como desarrollador,
quiero que el escaneo genere un modelo estructurado del proyecto,
para utilizar la información en diferentes vistas de DevVault.

Criterios de aceptación:

- El modelo debe incluir tecnologías, módulos, configuraciones, dependencias, servicios y relaciones.
- Cada elemento debe contar con un identificador y una referencia a su origen cuando corresponda.
- El modelo debe permitir incorporar nuevos tipos de elementos sin modificar toda la estructura.
- El resultado debe poder ser consumido por el frontend mediante la API REST.
- El sistema debe registrar los elementos que no pudo analizar.
Prioridad: P0 — MVP.

# Épica 2. Project Overview
El usuario debe poder comprender el estado general del proyecto sin navegar por múltiples secciones.

### HU-05 — Visualizar el resumen del proyecto
Como desarrollador,
quiero visualizar un resumen técnico del proyecto,
para conocer sus principales características.

Criterios de aceptación:

- Debe mostrar nombre, ruta y tipo de proyecto.
- Debe mostrar los lenguajes y frameworks detectados.
- Debe mostrar el número de módulos, configuraciones y servicios encontrados.
- Debe mostrar la fecha del último escaneo.
- Debe mostrar advertencias y hallazgos relevantes.
- Cada elemento del resumen debe permitir acceder a su sección correspondiente.
Prioridad: P0 — MVP.

### HU-06 — Consultar el estado del escaneo
Como desarrollador,
quiero conocer el estado del último escaneo,
para saber si la información del proyecto está actualizada.

Criterios de aceptación:

- El sistema debe mostrar si el escaneo está pendiente, en ejecución, completado o fallido.
- Debe mostrar la fecha del último escaneo exitoso.
- Debe permitir iniciar un nuevo escaneo.
- Durante el escaneo, la interfaz debe mostrar un indicador de progreso o actividad.
- Si el escaneo falla, debe conservar el último resultado exitoso.
Prioridad: P0 — MVP.

# Épica 3. Project Structure Explorer
El árbol de archivos debe ser más inteligente que un explorador convencional.

### HU-07 — Visualizar la estructura del proyecto
Como desarrollador,
quiero visualizar la estructura de directorios y archivos de mi proyecto,
para comprender cómo está organizado.

Criterios de aceptación:

- DevVault debe mostrar un árbol jerárquico de archivos y directorios.
- Debe permitir expandir y contraer directorios.
- Debe mostrar iconos o etiquetas para archivos de configuración relevantes.
- Debe permitir navegar por la estructura sin cargar todos los archivos simultáneamente.
- Debe respetar las reglas de exclusión configuradas para el escaneo.
Prioridad: P0 — MVP.

### HU-08 — Clasificar archivos y directorios
Como desarrollador,
quiero que DevVault clasifique los archivos según su función,
para identificar rápidamente las partes importantes del proyecto.

Criterios de aceptación:

- El sistema debe clasificar archivos como código fuente, configuración, documentación, pruebas, infraestructura y dependencias.
- Debe clasificar directorios como backend, frontend, base de datos, infraestructura o recursos cuando exista evidencia.
- Debe mostrar la categoría asignada en el explorador.
- La clasificación debe poder actualizarse después de un nuevo escaneo.
- Los elementos que no puedan clasificarse deben permanecer disponibles como elementos no clasificados.
Prioridad: P1.

### HU-09 — Consultar detalles de un archivo
Como desarrollador,
quiero consultar los detalles técnicos de un archivo,
para comprender su propósito dentro del proyecto.

Criterios de aceptación:

- Al seleccionar un archivo, DevVault debe mostrar su ruta, extensión y categoría.
- Debe mostrar las tecnologías relacionadas cuando existan.
- Debe mostrar las configuraciones o dependencias asociadas.
- Debe permitir abrir el archivo en el editor predeterminado del sistema, si la integración está disponible.
- No debe ejecutar ni modificar el archivo desde esta vista.
Prioridad: P1.

### HU-10 — Buscar archivos y elementos técnicos
Como desarrollador,
quiero buscar archivos, configuraciones y tecnologías dentro del proyecto,
para encontrar rápidamente información específica.

Criterios de aceptación:

- La búsqueda debe permitir encontrar archivos por nombre o ruta.
- Debe permitir buscar tecnologías y configuraciones detectadas.
- Los resultados deben mostrar la ubicación del elemento.
- Al seleccionar un resultado, debe abrir la sección correspondiente.
- La búsqueda debe actualizar los resultados mientras el usuario escribe.
Prioridad: P1.

# Épica 4. Tech Stack Explorer
Esta sección permite consultar las tecnologías agrupadas por su responsabilidad dentro del proyecto.

### HU-11 — Visualizar el stack tecnológico
Como desarrollador,
quiero visualizar las tecnologías agrupadas por categoría,
para comprender la composición tecnológica de mi proyecto.

Criterios de aceptación:

- Las tecnologías deben agruparse por categorías como frontend, backend, base de datos, infraestructura y herramientas.
- Cada tecnología debe mostrar su nombre y versión cuando esté disponible.
- La interfaz debe permitir seleccionar una tecnología para consultar sus detalles.
- Debe mostrar las tecnologías principales y secundarias.
- Debe permitir filtrar por categoría.
Prioridad: P0 — MVP.

### HU-12 — Consultar evidencia de una tecnología
Como desarrollador,
quiero conocer qué archivos permitieron detectar una tecnología,
para verificar la información obtenida por DevVault.

Criterios de aceptación:

- Cada tecnología detectada debe mostrar al menos una evidencia.
- La evidencia debe incluir la ruta del archivo y, cuando sea posible, la propiedad o dependencia relevante.
- Debe distinguirse entre detección directa e inferencia.
- El usuario debe poder navegar desde la tecnología hasta el archivo de origen.
- Si no existe evidencia suficiente, la tecnología no debe presentarse como una detección confirmada.
Prioridad: P0 — MVP.

### HU-13 — Consultar versiones tecnológicas
Como desarrollador,
quiero conocer las versiones de las tecnologías utilizadas,
para identificar los requisitos del proyecto.

Criterios de aceptación:

- DevVault debe detectar versiones desde archivos de configuración y manifiestos.
- Debe identificar versiones de Java, Node.js, PHP y otros runtimes soportados.
- Debe distinguir entre una versión requerida y una versión instalada, si ambas están disponibles.
- Debe indicar cuando una versión no pueda determinarse.
- Debe mostrar la fuente de la versión detectada.
Prioridad: P1.

# Épica 5. Architecture Map
Esta es una de las funcionalidades que puede darle mayor identidad a DevVault.

### HU-14 — Generar un mapa de arquitectura
Como desarrollador,
quiero visualizar un mapa de arquitectura de mi proyecto,
para comprender sus componentes y relaciones.

Criterios de aceptación:

- DevVault debe generar un grafo a partir del modelo técnico del proyecto.
- Los nodos deben representar módulos, servicios, bases de datos y componentes relevantes.
- Las conexiones deben representar relaciones detectadas o inferidas.
- El mapa debe permitir acercar, alejar y desplazarse.
- El usuario debe poder seleccionar un nodo para consultar sus detalles.
- El sistema debe indicar el tipo de evidencia que respalda cada relación.
Prioridad: P1.

### HU-15 — Filtrar el mapa de arquitectura
Como desarrollador,
quiero filtrar los elementos del mapa de arquitectura,
para visualizar únicamente los componentes relevantes.

Criterios de aceptación:

- Debe permitir filtrar por tipo de componente.
- Debe permitir ocultar o mostrar dependencias.
- Debe permitir visualizar únicamente frontend, backend, base de datos o infraestructura.
- El mapa debe actualizarse sin requerir un nuevo escaneo.
- Los filtros deben poder combinarse.
Prioridad: P1.

### HU-16 — Consultar relaciones entre componentes
Como desarrollador,
quiero consultar las relaciones entre los componentes del proyecto,
para comprender cómo interactúan entre sí.

Criterios de aceptación:

- Al seleccionar una relación, DevVault debe mostrar los componentes relacionados.
- Debe mostrar la evidencia que respalda la relación.
- Debe distinguir relaciones detectadas, inferidas y observadas en ejecución.
- Debe mostrar información adicional cuando la relación corresponda a una conexión entre servicios.
- Si no existe evidencia suficiente, no debe inventar una conexión.
Prioridad: P1.

### HU-17 — Exportar el mapa de arquitectura
Como desarrollador,
quiero exportar el mapa de arquitectura,
para documentar y compartir la estructura técnica del proyecto.

Criterios de aceptación:

- El usuario debe poder exportar el mapa como imagen.
- El sistema debe permitir exportar una representación compatible con herramientas de diagramación, si está soportada.
- La exportación debe conservar los nombres y las relaciones visibles.
- Debe permitir exportar el mapa completo o la vista filtrada.
Prioridad: P2.

# Épica 6. Configuration Explorer
El objetivo es centralizar el conocimiento sobre los archivos que definen el comportamiento del proyecto.

### HU-18 — Detectar archivos de configuración
Como desarrollador,
quiero que DevVault identifique los archivos de configuración del proyecto,
para conocer cómo está definido su funcionamiento.

Criterios de aceptación:

- Debe detectar archivos como `application.yml`, `application.properties`, `package.json`, `composer.json`, `build.gradle`, `pom.xml`, `Dockerfile`, `compose.yaml` y archivos equivalentes.
- Debe clasificar los archivos según su propósito.
- Debe mostrar la ruta y el tipo de configuración.
- Debe permitir consultar los detalles del archivo.
- La detección debe actualizarse después de un nuevo escaneo.
Prioridad: P0 — MVP.

### HU-19 — Consultar detalles de configuración
Como desarrollador,
quiero consultar las propiedades relevantes de un archivo de configuración,
para comprender su función dentro del proyecto.

Criterios de aceptación:

- DevVault debe mostrar el propósito del archivo.
- Debe extraer propiedades relevantes cuando el formato sea soportado.
- Debe mostrar las tecnologías relacionadas.
- Debe permitir navegar hacia los archivos relacionados.
- Debe informar cuando un archivo no pueda analizarse.
Prioridad: P1.

### HU-20 — Proteger información sensible
Como desarrollador,
quiero que DevVault proteja los valores sensibles de mis configuraciones,
para evitar exponer contraseñas, tokens o credenciales.

Criterios de aceptación:

- DevVault debe identificar nombres de variables potencialmente sensibles.
- Debe ocultar valores de contraseñas, tokens, claves y secretos.
- Debe evitar almacenar valores sensibles en el resultado del escaneo.
- Debe mostrar únicamente el estado de configuración cuando corresponda.
- Debe permitir distinguir una variable configurada de una variable faltante sin revelar su valor.
Prioridad: P0 — MVP.

# Épica 7. Dependency Explorer

### HU-21 — Detectar dependencias del proyecto
Como desarrollador,
quiero visualizar las dependencias declaradas en mi proyecto,
para conocer las librerías y herramientas utilizadas.

Criterios de aceptación:

- DevVault debe analizar los manifiestos de dependencias soportados.
- Debe mostrar el nombre, versión y tipo de cada dependencia cuando estén disponibles.
- Debe diferenciar dependencias de producción y desarrollo.
- Debe mostrar el archivo de origen.
- Debe permitir filtrar y buscar dependencias.
Prioridad: P0 — MVP.

### HU-22 — Visualizar relaciones de dependencias
Como desarrollador,
quiero visualizar las relaciones entre las dependencias y los componentes del proyecto,
para comprender cómo se compone su stack.

Criterios de aceptación:

- Debe permitir visualizar dependencias como nodos relacionados con el proyecto.
- Debe mostrar las relaciones directas cuando exista evidencia.
- Debe diferenciar dependencias directas y transitivas cuando la información esté disponible.
- Debe permitir seleccionar una dependencia para consultar sus detalles.
- No debe afirmar que una dependencia está siendo utilizada en el código si únicamente está declarada.
Prioridad: P1.

# Épica 8. Environment & Services

### HU-23 — Detectar requisitos del entorno
Como desarrollador,
quiero conocer los requisitos necesarios para ejecutar mi proyecto,
para preparar correctamente mi entorno de desarrollo.

Criterios de aceptación:

- DevVault debe detectar runtimes y herramientas requeridas.
- Debe identificar versiones requeridas desde archivos de configuración.
- Debe detectar variables de entorno necesarias cuando exista evidencia.
- Debe mostrar puertos y servicios declarados.
- Debe distinguir entre requisitos detectados y requisitos inferidos.
Prioridad: P1.

### HU-24 — Detectar servicios asociados al proyecto
Como desarrollador,
quiero que DevVault identifique los servicios asociados a mi proyecto,
para comprender sus dependencias externas.

Criterios de aceptación:

- Debe detectar servicios declarados en archivos Docker Compose.
- Debe identificar bases de datos, cachés y otros servicios soportados.
- Debe mostrar nombre, imagen, puertos y configuración relevante cuando esté disponible.
- Debe relacionar los servicios con los componentes que los utilizan cuando exista evidencia.
- Debe distinguir entre servicios declarados y servicios actualmente ejecutándose.
Prioridad: P1.

### HU-25 — Consultar el estado del entorno
Como desarrollador,
quiero consultar el estado de los servicios y requisitos de mi proyecto,
para identificar problemas que puedan impedir su ejecución.

Criterios de aceptación:

- Debe mostrar el estado de los servicios soportados.
- Debe mostrar los puertos configurados.
- Debe distinguir entre servicio detenido, ejecutándose y estado desconocido.
- Debe mostrar errores de conexión cuando puedan detectarse.
- Debe permitir actualizar el estado sin volver a escanear todo el proyecto.
Prioridad: P1.

# Épica 9. Scan History & Change Detection

### HU-26 — Consultar el historial de escaneos
Como desarrollador,
quiero consultar el historial de escaneos de un proyecto,
para conocer cómo ha evolucionado su estructura técnica.

Criterios de aceptación:

- DevVault debe almacenar un resumen de cada escaneo.
- Debe mostrar fecha, duración y resultado.
- Debe permitir consultar los resultados de escaneos anteriores.
- Debe conservar el último escaneo exitoso si uno posterior falla.
- El historial debe poder limitarse mediante una política de retención.
Prioridad: P1.

### HU-27 — Detectar cambios entre escaneos
Como desarrollador,
quiero comparar dos escaneos de un proyecto,
para identificar cambios en su stack, estructura y configuración.

Criterios de aceptación:

- Debe detectar tecnologías agregadas o eliminadas.
- Debe detectar cambios en versiones de dependencias.
- Debe detectar archivos de configuración nuevos, modificados o eliminados.
- Debe mostrar un resumen de los cambios.
- Debe permitir consultar los detalles de cada cambio.
- No debe comparar ni exponer valores sensibles.
Prioridad: P1.

# Épica 10. Findings & Diagnostics
Esta épica convierte el escaneo en una herramienta de diagnóstico.

### HU-28 — Generar hallazgos del escaneo
Como desarrollador,
quiero recibir hallazgos sobre la configuración y estructura de mi proyecto,
para identificar posibles problemas técnicos.

Criterios de aceptación:

- DevVault debe generar hallazgos a partir de reglas definidas.
- Cada hallazgo debe incluir título, descripción, severidad y evidencia.
- Debe distinguir entre información, advertencia y error.
- Debe permitir navegar hacia el archivo relacionado.
- Los hallazgos deben poder actualizarse mediante un nuevo escaneo.
Prioridad: P1.

### HU-29 — Consultar recomendaciones técnicas
Como desarrollador,
quiero recibir recomendaciones basadas en los hallazgos del escaneo,
para mejorar la configuración de mi proyecto.

Criterios de aceptación:

- Cada recomendación debe estar relacionada con un hallazgo.
- Debe explicar el problema y la acción sugerida.
- Debe mostrar la evidencia que justifica la recomendación.
- Debe distinguir recomendaciones automáticas de acciones ejecutables.
- DevVault no debe modificar archivos automáticamente sin autorización del usuario.
Prioridad: P2.