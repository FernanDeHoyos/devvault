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

DevVault solo puede iniciar los tipos de proyecto que reconoce y para los que
están instaladas sus herramientas. En la beta se reconocen proyectos Spring
Boot (Maven/Gradle), Node.js/React y PHP/Laravel. Si no se detecta un proyecto,
comprueba que registraste la carpeta correcta y vuelve a escanear.

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
redes no confiables. La sección **Settings** todavía no está disponible en
esta beta.
