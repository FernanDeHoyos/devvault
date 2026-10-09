# ADR — Preparación para plugins externos

- **Estado:** preparación aceptada; contrato y ejecución diferidos hasta después de v1.0.
- **Alcance:** registrar las restricciones arquitectónicas que evitan acoplar futuras extensiones al núcleo. No habilita la instalación ni la ejecución de plugins externos.

## Contexto actual

`TechnologyPlugin` es una interfaz del backend descubierta por el escaneo de componentes de Spring. `PluginRegistrySeeder` registra esas implementaciones en `plugin_descriptors`, y la API actual permite habilitarlas o deshabilitarlas. Por tanto, el mecanismo existente es un registro de plugins incluidos en el classpath, no un cargador de paquetes externos.

## Objetivo futuro

Permitir que autores desarrollen extensiones para DevVault y que cada usuario las instale para su instancia, sin modificar la lógica de los módulos centrales por cada extensión. La futura integración sí requerirá un punto de extensión estable en el host; lo que se evita es que el código de cada plugin dependa de detalles internos de Spring, JPA o de módulos de DevVault.

## Límites arquitectónicos que se preservan desde ahora

1. Los módulos del núcleo no deben depender de implementaciones concretas de plugins.
2. Los contratos futuros no deben exponer entidades JPA, repositorios, controladores ni el `ApplicationContext` de Spring.
3. La información que cruce el límite del plugin debe usar contratos versionados y tipos neutrales; los fallos de una extensión deben poder identificarse y limitarse a esa extensión.
4. El registro actual de plugins incluidos debe seguir funcionando durante la migración. Sus identificadores y estado habilitado/deshabilitado no deben confundirse con la instalación de un paquete externo.
5. Añadir un plugin futuro no debe exigir cambios en el detector o runtime central, salvo una evolución deliberada y versionada del SDK.

## Decisiones diferidas

La versión que implemente plugins externos deberá decidir, con requisitos concretos, lo siguiente:

- **Modelo de ejecución:** cargador Java en el proceso de DevVault para extensiones de confianza, o proceso separado con protocolo local para aislamiento más fuerte.
- **Confianza y permisos:** procedencia, consentimiento de instalación, acceso a archivos, procesos, red y secretos. Un `ClassLoader` separado organiza dependencias, pero no es una frontera de seguridad.
- **SDK y compatibilidad:** módulo/API independiente, versión del contrato, versión mínima del host, manifiesto, dependencias y política de actualización.
- **Ciclo de vida:** ubicación de instalación, validación, activar/desactivar, actualización, errores, reinicio requerido y eventual descarga en caliente.
- **Capacidades:** tipos de extensión que necesita el producto. Los ejemplos posibles incluyen detección de tecnología, configuración de ejecución, catálogo de rutas o apertura en un editor; no constituyen aún una API comprometida.

## Nota sobre el módulo editor

Abrir un proyecto en el IDE introduce el cuarto tipo de ejemplo, y conviene dejar por escrito por qué no lo convierte en un SPI.

`EditorCatalog` es un catálogo cerrado y compilado, no un registro de extensiones. La razón es de seguridad, no de diseño: el módulo ejecuta un binario en la máquina del usuario, así que aceptar un comando libre convertiría `POST /projects/{id}/open` en ejecución remota de comandos. Lo único entre ese endpoint y el sistema es que la API está enlazada a loopback y que `SameOriginFilter` rechaza las peticiones con un `Origin` ajeno.

Si en el futuro se admite un editor de terceros, esa extensión entra por el mismo SDK que el resto y queda sujeta a los cinco límites de arriba. El catálogo incluido no se expone como API pública antes de que exista ese SDK con sus garantías de seguridad.

## Camino de evolución

1. **Ahora, durante v1.0:** mantener los plugins incluidos; no aceptar JARs o paquetes ejecutables de terceros. Mantener los límites descritos aquí y completar primero autenticación y seguridad de credenciales.
2. **Versión posterior — definición:** validar casos de uso de autores externos y elegir confianza/aislamiento; cerrar SDK, manifiesto, compatibilidad y ciclo de vida con un plugin de ejemplo fuera del repositorio principal.
3. **Versión posterior — implementación:** añadir gestor de paquetes y ejecución conforme al contrato aprobado, migrando gradualmente los plugins incluidos a ese SPI sin romper el registro existente.

## Consecuencias

- El núcleo actual no necesita un cargador dinámico ni endpoints de instalación para completar v1.0.
- La extensibilidad pública no debe anunciarse como disponible hasta que haya SDK, documentación para autores y garantías de seguridad explícitas.
- Si un caso de uso futuro requiere plugins no confiables, no se deben ejecutar sus JARs dentro del proceso principal suponiendo que el classloader los aísla.
