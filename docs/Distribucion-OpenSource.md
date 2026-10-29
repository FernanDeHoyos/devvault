# Preparar la distribución open source

Estado de la preparación de DevVault para publicar código fuente y una primera
versión descargable. La plataforma objetivo es Windows y Linux; macOS queda
fuera del primer lanzamiento.

## Estado actual

| Área | Estado | Pendiente |
|---|---|---|
| Licencia backend | Apache-2.0 en `LICENSE` | Confirmar que todos los archivos propios tienen derechos de distribución |
| Licencia UI | Apache-2.0 en el repositorio UI y en el submódulo | Confirmar en CI que el commit de UI fijado por el submódulo se puede descargar |
| Metadatos UI | `package.json` declara `Apache-2.0` | Mantenerlo junto al archivo de licencia |
| CI | Workflow para Windows y Ubuntu con tests, lint, build integrado y smoke tests | Revisar que quede verde después de ordenar los cambios y subir el workflow |
| Paquete Windows | Empaquetador de preview incluye runtime, licencia y scripts de inicio/cierre | Validar el ZIP desde una máquina limpia |
| Paquete Linux | Empaquetador `.tar.gz` con JAR/UI, runtime Java 17, licencia y scripts; workflow configura artefacto CI por 14 días | Ejecutar el empaquetado y smoke test en Ubuntu CI; revisar el artefacto descargado |
| Releases | Al subir un tag `v*`, el workflow publica un GitHub Release después de que ambas plataformas pasen CI | Revisar los paquetes/checksums del tag antes de crearlo; el tag hace pública la descarga |
| Dependencias | Backend y UI descargan dependencias durante build; Windows preview incluye runtime Java | Revisar licencias de dependencias y conservar atribuciones/licencias al redistribuir binarios |
| Guía para usuarios | README documenta las previews de Windows y Linux | Completar la guía de instalación para los archivos de un release público |
| Contribuciones y seguridad | No hay `CONTRIBUTING.md` ni `SECURITY.md` | Publicar cómo proponer cambios y reportar vulnerabilidades de forma privada |

## Orden recomendado para la primera beta

1. Revisar y publicar los cambios pendientes de distribución en el backend.
   El commit del submódulo ya fija la UI que se quiere incluir.
2. Confirmar que CI puede descargar ese commit de UI y pasa en Windows y
   Ubuntu, incluidos los empaquetadores.
3. Auditar las dependencias incluidas en JAR y UI, y añadir los avisos de
   terceros necesarios a los paquetes descargables.
4. Construir y verificar paquetes de Windows y Linux desde entornos limpios.
   Documentar Docker, Java y otros requisitos de los proyectos que DevVault
   puede iniciar.
5. Añadir `CONTRIBUTING.md`, `SECURITY.md`, instrucciones de instalación y
   notas de limitaciones conocidas.
6. Crear el tag `v0.1.0-beta.1` desde un commit limpio. Si las pruebas y ambos
   empaquetadores pasan, el workflow publica automáticamente los paquetes,
   notas generadas y checksums SHA-256 en GitHub Releases como prerelease.

## Condiciones de lanzamiento

- Backend y UI tienen licencia consistente y sus commits están publicados.
- CI pasa en Windows y Ubuntu, incluidos los smoke tests disponibles.
- Los paquetes descargados arrancan y se detienen en una instalación limpia.
- Las licencias/avisos de dependencias redistribuidas están disponibles en cada
  paquete.
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
