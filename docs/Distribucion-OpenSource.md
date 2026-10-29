# Preparar la distribución open source

Estado de la preparación de DevVault para publicar código fuente y una primera
versión descargable. La plataforma objetivo es Windows y Linux; macOS queda
fuera del primer lanzamiento.

## Estado actual

| Área | Estado | Pendiente |
|---|---|---|
| Licencia backend | Apache-2.0 en `LICENSE` | Confirmar que todos los archivos propios tienen derechos de distribución |
| Licencia UI | Apache-2.0 en el repositorio UI y en el submódulo | Confirmar y publicar primero el commit de UI; después actualizar el puntero del submódulo |
| Metadatos UI | `package.json` declara `Apache-2.0` | Mantenerlo junto al archivo de licencia |
| CI | Workflow para Windows y Ubuntu con tests, lint, build integrado y smoke tests | Revisar que quede verde después de ordenar los cambios y subir el workflow |
| Paquete Windows | Existe un empaquetador de preview y scripts de inicio/cierre | Validar el paquete desde una máquina limpia y describir los requisitos de descarga |
| Paquete Linux | No existe empaquetador de distribución | Crear inicialmente un `.tar.gz` con JAR/UI integrados, runtime Java compatible y scripts de inicio/cierre |
| Releases | No hay workflow de publicación por tags | La primera beta puede publicarse manualmente en GitHub Releases |
| Dependencias | Backend y UI descargan dependencias durante build; Windows preview incluye runtime Java | Revisar licencias de dependencias y conservar atribuciones/licencias al redistribuir binarios |
| Guía para usuarios | README explica principalmente el desarrollo desde los repositorios | Añadir una sección de descarga, instalación, actualización y resolución de errores |
| Contribuciones y seguridad | No hay `CONTRIBUTING.md` ni `SECURITY.md` | Publicar cómo proponer cambios y reportar vulnerabilidades de forma privada |

## Orden recomendado para la primera beta

1. Terminar la revisión de cambios locales y separar los commits de backend y
   UI. El submódulo debe apuntar al commit de UI que se quiere lanzar.
2. Subir código, licencias y workflow a GitHub; comprobar las ejecuciones de
   CI en Windows y Ubuntu.
3. Auditar las dependencias incluidas en JAR y UI, y añadir los avisos de
   terceros necesarios a los paquetes descargables.
4. Construir y verificar paquetes de Windows y Linux desde entornos limpios.
   Documentar Docker, Java y otros requisitos de los proyectos que DevVault
   puede iniciar.
5. Añadir `CONTRIBUTING.md`, `SECURITY.md`, instrucciones de instalación y
   notas de limitaciones conocidas.
6. Crear el tag `v0.1.0-beta.1` desde un commit limpio y publicar los archivos,
   notas de la versión y checksums SHA-256 en GitHub Releases.

## Condiciones de lanzamiento

- Backend y UI tienen licencia consistente y sus commits están publicados.
- CI pasa en Windows y Ubuntu, incluidos los smoke tests disponibles.
- Los paquetes descargados arrancan y se detienen en una instalación limpia.
- El README advierte que DevVault es local, no tiene autenticación y no debe
  exponerse a redes no confiables.
- La versión y sus archivos descargables corresponden al mismo tag y commit.

## Registro

### 2026-10-29

- Se eligió Apache-2.0 para backend y UI.
- Los archivos `LICENSE` del backend, submódulo UI y repositorio UI independiente
  están sincronizados y contienen el titular indicado por el propietario.
- El siguiente trabajo prioritario es publicar/revisar los cambios en GitHub y
  completar la distribución Linux y los avisos de dependencias antes de una
  beta pública.
