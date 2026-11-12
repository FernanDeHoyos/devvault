# Entornos reutilizables y diagnóstico integrado

DevVault permite guardar conjuntos de proyectos con dependencias explícitas,
iniciarlos por etapas y detenerlos en orden inverso. El historial se conserva
entre reinicios; los pasos fallidos e interrumpidos quedan visibles para reintento.

El detalle del proyecto reúne requisitos, estado de servicios, puertos y enlaces
a logs. El mapa añade estados de runtime para coincidencias exactas de servicios
y distingue esa información de las relaciones estáticas detectadas en archivos.

## Cambios incluidos

- Ajustes de editor, apertura automática y frecuencia de métricas; detectores
  configurables y comprobación de conexión.
- Primeros pasos para registrar, escanear y ejecutar proyectos.
- Consulta agregada de métricas con resultados parciales y CPU Docker calculada
  entre muestras, sin espera artificial por contenedor.
- Reconexión de logs con espera progresiva y limpieza al cambiar de proyecto.
- Avisos de automatización y sustitución literal de campos en mensajes.
- Validación de host local y condiciones de automatización restringidas.
- Bloqueo transaccional por proyecto para rechazar inicios, detenciones y borrados
  concurrentes hasta que la operación en curso haya persistido su resultado.
- Extensiones declarativas, variables requeridas, recetas y diagnóstico previo
  integrados con el trabajo existente en la copia local.
- Pruebas de navegador, validación de integración obligatoria en Linux y
  benchmark reproducible de escaneo.
- Lanzadores que respetan una conexión PostgreSQL ya configurada.

## Actualización

La migración Flyway V12 crea las tablas de entornos e historial. No modifica las
carpetas de los proyectos. Las preferencias de interfaz se guardan en el navegador;
los entornos y la habilitación de detectores se guardan en la base de datos.

Revisar reglas antiguas que invoquen métodos en sus condiciones: ahora se permiten
comparaciones y operaciones del lenguaje restringido, sin constructores ni clases.
Los mensajes solo sustituyen nombres de campos como `#{projectId}`.

## Límites explícitos

- El almacenamiento embebido no se añadió: PostgreSQL sigue siendo el motor
  soportado. La evaluación y los criterios de migración están en el ADR.
- No se añadió caché incremental; se midió el escaneo antes de tomar esa decisión.
- Los contratos nuevos tienen declaraciones TypeScript/JSDoc; el frontend sigue
  siendo JavaScript, sin migración completa a TypeScript.
- Las pruebas de navegador usan fixtures de API. Las pruebas Java con PostgreSQL
  y los smoke tests cubren la integración real por separado.
- La ejecución de entornos tiene un límite global de una operación a la vez y
  hasta cuatro proyectos independientes por etapa.

Consulta `Plan-Siguiente-Nivel.md` para los resultados y límites de validación.
