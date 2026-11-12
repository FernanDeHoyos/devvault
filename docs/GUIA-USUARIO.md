# Guía de uso de DevVault

Guía inicial para la beta `0.1.0-beta.1`. Explica el flujo principal para
registrar proyectos, iniciarlos y consultar su estado. Los nombres entre
comillas corresponden a las opciones que aparecen en la interfaz.

## 1. Antes de empezar

1. Instala y ejecuta Docker Desktop (Windows) o Docker Engine (Linux). DevVault
   usa PostgreSQL en Docker para guardar sus datos.
2. Descarga el paquete de tu sistema desde
   [GitHub Releases](https://github.com/FernanDeHoyos/devvault/releases) y
   sigue el `LEEME.txt` incluido para extraerlo e iniciar DevVault.
3. Abre en el navegador la dirección local que indique el iniciador.

El paquete incluye Java y la interfaz. Para iniciar proyectos, también debes
tener instalado el entorno que cada uno necesite, por ejemplo Node.js/npm o
PHP/Composer.

## 2. Registrar una carpeta de proyectos

1. Abre **Workspace** en el menú.
2. Selecciona la carpeta que contiene tus proyectos o pega su ruta. Puedes
   asignarle un nombre; si lo dejas vacío, se usa el nombre de la carpeta.
3. Pulsa **Añadir workspace**.
4. Pulsa **Escanear ahora** en ese workspace.
5. Cuando termine, abre **Ver y ejecutar** para consultar los proyectos que
   DevVault detectó.

Un workspace es solo una carpeta que DevVault examina. Quitar el workspace
elimina de DevVault los proyectos registrados y sus datos; no borra los
archivos de la carpeta. Si un proyecto está ejecutándose, DevVault lo detiene
antes de quitarlo.

## 3. Buscar y ejecutar un proyecto

En **Projects** puedes buscar por nombre, ruta, lenguaje o framework; filtrar
por estado y ordenar la lista. Selecciona una tarjeta para abrir sus detalles.

- Pulsa **Start** para iniciar el proyecto. El proceso puede tardar mientras
  se comprueba que el servicio esté listo.
- Si aparece un puerto disponible, pulsa **Abrir en localhost** para abrir la
  aplicación en el navegador.
- Pulsa **Stop** para detener los servicios que DevVault inició para ese
  proyecto.
- Pulsa **Abrir en el editor** para abrir su carpeta en un editor detectado.
  Esta acción no inicia ni detiene el proyecto.

En los detalles del proyecto, **Arquitectura del proyecto** organiza el análisis
en secciones: **Resumen**, **Estructura**, **Stack y mapa**, **Dependencias** e
**Infraestructura**. El resumen presenta los conteos y tecnologías principales;
la estructura contiene un árbol expandible de archivos y módulos; y las demás
secciones permiten consultar el detalle cuando lo necesitas. Puedes filtrar el
stack por categoría y abrir el archivo de evidencia desde cada tarjeta.
El mapa de arquitectura es un diagrama agrupado por tipo de componente; sus
tarjetas de detalle y los inventarios extensos tienen un área de desplazamiento
propia para que no ocupen toda la página.
Los grupos del diagrama se pueden seleccionar para filtrar el detalle.
**Dashboard** y **Monitoring** incluyen gráficas de CPU y memoria con un
selector para ver todos los proyectos activos o uno individual. Las muestras
se consultan cada dos segundos y la vista conserva hasta cinco minutos; la
memoria mostrada no equivale a la RAM total del equipo.

El árbol omite directorios generados y enlaces simbólicos, y limita la cantidad
de contenido mostrado para que proyectos grandes sigan siendo navegables.

En **Bases de datos**, “Configuración declarada” significa que se encontró un
proveedor o URL en un archivo; “Driver declarado” indica una dependencia; y
“Servicio declarado” indica un servicio Compose. Ninguno de esos estados
confirma una conexión o que el servicio esté en ejecución. El mapa relaciona
el proyecto con componentes declarados y no es un grafo de llamadas ni afirma
conexiones que el escaneo no pudo verificar.

El escáner identifica evidencia de Java/Kotlin, JavaScript/TypeScript, PHP,
Python, HTML, CSS y SQL, además de frameworks y herramientas declarados en los
manifiestos compatibles. La detección no implica que DevVault pueda iniciar
cualquier proyecto: en esta beta, el inicio automático está limitado a los
tipos configurados (Spring Boot, Node.js y PHP) y a las herramientas instaladas
en el equipo. Si no se detecta un proyecto, comprueba la carpeta y vuelve a
escanear.

## 4. Consultar logs y estado

- **Logs:** elige un proyecto para seguir los logs de sus servicios activos.
  Puedes seleccionar un servicio, buscar texto, filtrar por nivel y exportar
  las líneas visibles como `.log`.
- **Monitoring:** muestra métricas actuales de CPU y memoria de los procesos y
  contenedores iniciados por DevVault. También permite revisar alertas y
  resolverlas.
- **Docker:** muestra los servicios Docker Compose que DevVault inició. Desde
  aquí puedes abrir los logs, acceder al puerto publicado o detener el
  proyecto. No lista contenedores iniciados fuera de DevVault.
- **Puertos:** muestra los puertos detectados y posibles conflictos. DevVault
  informa de conflictos, pero no detiene procesos externos.
- **Dashboard:** ofrece un resumen de proyectos y runtimes activos.

## 5. Git y automatizaciones

En los detalles de un proyecto Git puedes consultar ramas, commits, estado de
cambios y relación con el repositorio remoto. La acción **Fetch** consulta
cambios del remoto; no hace merge ni modifica tus archivos.

**Automation** permite crear reglas cuando un proyecto inicia o falla. En esta
beta, la acción disponible escribe un mensaje en el log del backend; no
reinicia ni repara proyectos automáticamente.

## 6. Si algo no funciona

- Si DevVault no carga, confirma que el backend sigue abierto y que Docker está
  en ejecución.
- Si el escaneo no encuentra proyectos, comprueba la ruta y pulsa **Escanear
  ahora** otra vez.
- Si el inicio falla, revisa el mensaje en los detalles del proyecto y sus
  logs. Verifica también que las herramientas requeridas por el proyecto estén
  instaladas.
- Si el puerto está ocupado, **Puertos** puede mostrar el proceso que lo usa.
  DevVault no lo cerrará por ti.

DevVault es una aplicación local, sin autenticación, y no debe exponerse a
redes no confiables.

## 7. Ajustes y diagnóstico

En **Settings / Ajustes** puedes elegir editor, frecuencia de métricas y apertura
automática de la aplicación. Estas preferencias se guardan en el navegador.
Los detectores de escaneo se habilitan en el servidor; vuelve a escanear para
actualizar los proyectos. La misma pantalla permite comprobar API y base de datos.

El detalle de un proyecto reúne servicios, puertos en conflicto, requisitos de
arranque y enlaces a logs. El mapa conserva relaciones detectadas en archivos;
añade estado y puerto cuando un servicio coincide por nombre exacto con el runtime.
Esto no significa que se haya observado tráfico entre componentes.

## 8. Entornos reutilizables

Abre **Entornos**, elige un workspace y selecciona los proyectos habituales.
Declara qué proyectos deben estar listos antes de cada uno y guarda el entorno.
Se rechazan ciclos y dependencias fuera del grupo. Los proyectos independientes
se inician en paralelo, hasta cuatro a la vez. La detención sigue el orden inverso.

Si un paso falla, los pasos posteriores se omiten. Revisa el diagnóstico y pulsa
**Iniciar / reintentar**: los proyectos ya activos se conservan. Una sola operación
de entorno puede ejecutarse a la vez. El historial persiste al reiniciar DevVault;
las operaciones interrumpidas quedan señaladas para revisión, sin reanudación automática.
Eliminar una definición borra su historial, pero no detiene sus proyectos ni borra archivos.

## 9. Avisos de automatización

La acción **NOTIFY** crea un aviso visible en DevVault y una alerta consultable en
Monitoring. **LOG** escribe en el registro del backend. Los mensajes sustituyen
campos como `#{projectId}` y `#{reason}` literalmente. Las condiciones permiten
comparaciones como `#reason == 'puerto ocupado'`; no ejecutan métodos, constructores
ni referencias a clases. Revisa las reglas anteriores que usaran esas operaciones.

## 10. PostgreSQL existente

Los paquetes aceptan `DEVVAULT_DB_URL`, `DEVVAULT_DB_USERNAME` y
`DEVVAULT_DB_PASSWORD`. Cuando configuras una base existente, los lanzadores no
requieren Docker para la base de DevVault ni detienen esa base al cerrar.
Los proyectos que utilizan Docker Compose siguen necesitando Docker.
