# Preparar la distribución open source

Estado de la preparación de DevVault para publicar código fuente y una primera
versión descargable. La plataforma objetivo es Windows y Linux; macOS queda
fuera del primer lanzamiento.

## Estado actual

| Área | Estado | Pendiente |
|---|---|---|
| Licencia del código propio | Backend y UI declaran Apache-2.0; el submódulo está fijado al commit `da0da430edd095acc0a0f19d0917c603e22ef6dc` | Confirmar titularidad/derechos de todos los archivos antes de publicar |
| CI | Run [40998188349](https://github.com/FernanDeHoyos/devvault/actions/runs/40998188349), commit `5268fae596326bcb28cbf2b768506b834c22b82f`: Windows y Ubuntu en verde; smoke tests y empaquetado completados | Repetir después de cualquier cambio de distribución |
| Paquetes Windows y Linux | Se descargaron los artefactos de CI y ambos checksums internos coinciden. Windows se extrajo y su runtime reporta Java 17.0.20.1; Linux se extrajo en WSL | El arranque integral no se pudo validar: el contenedor aislado falló al retirar su capa `overlay2` por un filesystem de solo lectura; el runtime Linux tampoco pudo leer `libpthread.so.0` en WSL |
| Dependencias | El lockfile de UI declara MIT/ISC. El runtime Java incluye dependencias bajo Apache-2.0, MIT, LGPL, EPL, EDL y GPL-2.0 con Classpath Exception | Falta una revisión completa de avisos y obligaciones de redistribución, especialmente las dependencias con copyleft; los empaquetadores solo añaden `LICENSE` |
| Instalación | README ahora incluye descarga de Releases, verificación SHA-256, inicio/cierre y requisitos | Verificar los comandos contra paquetes finales y actualizar cualquier diferencia |
| Contribuciones y seguridad | Añadidos `CONTRIBUTING.md` y `SECURITY.md` | Habilitar y comprobar en la configuración de GitHub el reporte privado de vulnerabilidades |
| Releases | El workflow publica al subir un tag `v*` si pasan ambos jobs de plataforma | No crear el tag hasta cerrar los pendientes anteriores; no hay tags de release todavía |

## Resultado de la revisión previa al lanzamiento

La última corrida verde confirma build, pruebas y generación de paquetes. Los
checksums de los artefactos descargados también coinciden; sin embargo, el
arranque desde una instalación limpia sigue sin verificarse debido a errores
del entorno Docker/WSL, no a un fallo confirmado del código. El intento dejó
detenido un contenedor de prueba aislado (`devvault-cleanverify-win-5268`), pero
Docker no pudo retirarlo porque su filesystem de overlay informó solo lectura.
No se reinició Docker para evitar afectar otros contenedores o datos del equipo.

La revisión de dependencias encontró componentes Java con licencias distintas
de Apache-2.0, incluidas LGPL y EPL; no se debe asumir que la licencia del
código propio cubre esos componentes. Los paquetes actuales incluyen `LICENSE`
y avisos dispersos dentro de algunos JAR/runtime, pero no un aviso consolidado
de terceros. Hay que documentar las dependencias y evaluar sus obligaciones de
distribución antes de hacer público un release.

## Orden recomendado para la primera beta

1. Revisar titularidad de los archivos propios y publicar los cambios
   pendientes en backend y UI.
2. Completar la auditoría de licencias de cada dependencia distribuida,
   documentar atribuciones y avisos, e incluirlos en ambos paquetes. Revisar
   en particular los términos LGPL/EPL y el runtime Java incluido.
3. Instalar los ZIP y `.tar.gz` descargados en sistemas limpios, comprobar
   inicio, health endpoint, UI, cierre y persistencia de PostgreSQL, y validar
   los checksums.
4. Confirmar los requisitos y limitaciones de Windows/Linux en el README y en
   los archivos `LEEME.txt` de los paquetes.
5. Habilitar y probar los reportes privados de vulnerabilidades en la
   configuración del repositorio.
6. Crear el tag `v0.1.0-beta.1` desde un commit limpio. Si las pruebas y ambos
   empaquetadores pasan, el workflow publica automáticamente los paquetes,
   notas generadas y checksums SHA-256 en GitHub Releases como prerelease.

## Condiciones de lanzamiento

- Backend y UI tienen licencia consistente y sus commits están publicados.
- CI pasa en Windows y Ubuntu, incluidos los smoke tests disponibles.
- Los paquetes descargados arrancan y se detienen en una instalación limpia.
- Las licencias/avisos de dependencias redistribuidas están disponibles en cada
  paquete.
- Las obligaciones de licencias recíprocas y el runtime Java redistribuido se
  han revisado.
- Los reportes privados de vulnerabilidades están habilitados y probados.
- El README advierte que DevVault es local, no tiene autenticación y no debe
  exponerse a redes no confiables.
- La versión y sus archivos descargables corresponden al mismo tag y commit.

## Registro

### 2026-10-29

- Se eligió Apache-2.0 para backend y UI.
- Los archivos `LICENSE` del backend, submódulo UI y repositorio UI independiente
  están sincronizados y contienen el titular indicado por el propietario.
- Se añadió un empaquetador portable Linux y el empaquetado Windows incluye
  `LICENSE`; el workflow construye ZIP/tar.gz y sus checksums en cada ejecución.
- Se configuró la publicación automática de un prerelease al subir tags `v*`,
  condicionada al éxito de las pruebas y empaquetadores en ambos sistemas.
- Falta ejecutar este workflow desde GitHub, auditar avisos de terceros,
  comprobar ambos artefactos y luego crear el primer tag de lanzamiento.

### 2026-10-30

- El run `40998188349` pasó en Ubuntu y Windows y produjo los dos artefactos.
- Los checksums descargados coinciden con los archivos `.sha256` de ambos
  paquetes: Windows `7528e907...0463e5fb`, Linux `1f319665...55f3d04e7`.
- La extracción del ZIP funciona y el Java incluido en Windows responde
  `17.0.20.1`. El arranque desde paquete se intentó en un volumen Docker nuevo,
  pero el daemon falló al retirar una capa `overlay2` de solo lectura; en WSL,
  el runtime Linux no pudo leer `libpthread.so.0` (error de I/O).
- Se añadió en README la guía de descarga e instalación de Releases y se
  publicaron pautas iniciales de contribución y reporte de vulnerabilidades.
- La inspección del runtime Java encontró dependencias con licencias Apache,
  MIT, LGPL, EPL, EDL y GPL-2.0 con Classpath Exception. Los paquetes del CI no
  contienen un aviso consolidado de terceros; la revisión y documentación de
  obligaciones sigue siendo un bloqueo para publicar.
