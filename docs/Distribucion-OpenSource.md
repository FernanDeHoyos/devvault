# Preparar la distribución open source

Estado de la preparación de DevVault para publicar código fuente y una primera
versión descargable. La plataforma objetivo es Windows y Linux; macOS queda
fuera del primer lanzamiento.

## Estado actual

| Área | Estado | Pendiente |
|---|---|---|
| Licencia del código propio | Backend y UI declaran Apache-2.0; el submódulo está fijado al commit `da0da430edd095acc0a0f19d0917c603e22ef6dc` | Confirmar titularidad/derechos de todos los archivos antes de publicar |
| CI | Run [40998188349](https://github.com/FernanDeHoyos/devvault/actions/runs/40998188349), commit `5268fae596326bcb28cbf2b768506b834c22b82f`: Windows y Ubuntu en verde; smoke tests y empaquetado completados | Repetir después de cualquier cambio de distribución |
| Paquetes Windows y Linux | El usuario confirmó que probó DevVault en máquinas limpias y funciona | Conservar el resultado de la verificación de ambos sistemas; confirmar que los paquetes se descargaron del mismo commit que se publicará |
| Dependencias | El empaquetador Windows genera un reporte Java, lista exacta de JARs, SBOM CycloneDX de dependencias npm de producción y copia los textos de licencia disponibles | Paquete Windows validado localmente; falta validar el empaquetador Linux en CI y revisar con especialista LGPL/EPL/GPL con Classpath Exception y entradas sin texto incluido |
| Instalación | README ahora incluye descarga de Releases, verificación SHA-256, inicio/cierre y requisitos | Verificar los comandos contra paquetes finales y actualizar cualquier diferencia |
| Contribuciones y seguridad | Añadidos `CONTRIBUTING.md` y `SECURITY.md` | Habilitar y comprobar en la configuración de GitHub el reporte privado de vulnerabilidades |
| Releases | El workflow publica al subir un tag `v*` si pasan ambos jobs de plataforma | No crear el tag hasta cerrar los pendientes anteriores; no hay tags de release todavía |

## Resultado de la revisión previa al lanzamiento

El usuario confirmó que DevVault funciona en máquinas limpias. La inspección
anterior de los artefactos de CI también confirmó que los checksums descargados
coincidían. El intento local de instalación no fue concluyente por errores de
Docker/WSL, pero ya no es el bloqueo informado para las pruebas en máquinas
limpias. Para el release, hay que asegurarse de validar los artefactos
generados desde el commit/tag que se va a publicar.

La revisión preliminar de dependencias encontró componentes Java con licencias
distintas de Apache-2.0, incluidas LGPL y EPL; no se debe asumir que la licencia
del código propio cubre esos componentes. Se añadió
[`docs/THIRD-PARTY-NOTICES.md`](./THIRD-PARTY-NOTICES.md) y el empaquetado ahora
incluye el reporte de dependencias Java, un SBOM CycloneDX de la UI y los
textos de licencia/aviso presentes en los componentes. Este material es un
paquete técnico para revisión especializada, no es una autorización legal para
redistribuir. Hay que resolver las licencias desconocidas o sin texto incluido
y validar las obligaciones aplicables antes de hacer público un release.
La preview local `build/distribution/DevVault-0.4.0-license-review-windows-x64.zip`
incluye el inventario Java (85 JARs empaquetados; 113 textos de licencia/aviso
extraídos), el SBOM UI (10 dependencias de producción y sus textos) y pasó el
empaquetado Windows. Se ejecutaron también las pruebas Gradle. Es un artefacto
para iniciar la revisión, no el release final: hay que regenerarlo desde el
commit que se someta a aprobación y completar CI en Linux.

## Orden recomendado para la primera beta

1. Revisar titularidad de los archivos propios y publicar los cambios
   pendientes en backend y UI.
2. Ejecutar el empaquetado, entregar al especialista los inventarios y textos
   de ambos paquetes, resolver cada observación y documentar su aprobación.
3. Confirmar que las pruebas en máquinas limpias corresponden a los paquetes
   finales de Windows y Linux y que se validaron inicio, cierre y checksum.
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
- El especialista ha revisado los inventarios/avisos correspondientes a los
  paquetes exactos del tag y ha cerrado las observaciones legales.
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

### 2026-11-01

- El usuario confirmó que probó DevVault en máquinas limpias y funciona.
- La validación queda pendiente de enlazar al commit exacto del release; la
  auditoría de licencias y los avisos consolidados siguen siendo bloqueos.

### 2026-11-01 - Inventario técnico

- Se añadió un borrador de avisos para el especialista, el reporte de
  dependencias Java de `runtimeClasspath`, un SBOM CycloneDX de las dependencias
  npm de producción y la recopilación de textos de licencia disponibles.
- Ambos empaquetadores ahora incluirán esos informes y textos en
  `third-party-notices/`. Falta ejecutar CI y revisar sus paquetes exactos.
- No es una aprobación legal: las obligaciones de redistribución y cada
  licencia no resuelta requieren revisión especializada.
