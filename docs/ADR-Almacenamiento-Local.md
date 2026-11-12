# Almacenamiento local y primer arranque

## Decisión de esta entrega

Mantener PostgreSQL como almacenamiento soportado. Los paquetes ahora respetan
`DEVVAULT_DB_URL`: si se configura una base existente, no exigen Docker ni levantan
el contenedor. Se conservan `DEVVAULT_DB_USERNAME` y `DEVVAULT_DB_PASSWORD`.
Si no se configura una URL, continúa el arranque automático con Docker.

## Evidencia en el código

- V2 usa JSONB e índice GIN en `project_profiles.raw_markers`.
- Las entidades de acciones y perfiles usan tipos JSON específicos de Hibernate.
- Las migraciones usan UUID, `gen_random_uuid()` y TIMESTAMPTZ.
- Flyway y las pruebas de integración actuales están vinculados a PostgreSQL.

Por tanto, cambiar únicamente la URL JDBC a SQLite o H2 no conserva el contrato
actual de esquema. Esta conclusión proviene de inspección, no de una prueba de
compatibilidad con esos motores.

## Alternativas y aceptación antes de cambiar el almacenamiento predeterminado

1. PostgreSQL existente: implementado en los lanzadores; requiere que el usuario
   aprovisione la base. No es almacenamiento embebido.
2. PostgreSQL empaquetado: conserva el esquema, pero necesita distribución de
   binarios por plataforma, ciclo de vida, actualizaciones y política de respaldo.
3. SQLite/H2: requiere migraciones por dialecto, adaptación de JSON y pruebas de
   concurrencia. No se añadió soporte parcial que pueda afectar datos existentes.

El prototipo futuro debe importar/exportar un workspace con perfiles, recetas,
reglas, alertas y entornos; conservar identificadores y relaciones; validar
reinicio tras cierre abrupto, backup/restauración y migración desde una copia de
PostgreSQL. Hasta cumplir estos criterios, no migrar datos de usuarios.

## Uso con PostgreSQL existente

Define `DEVVAULT_DB_URL=jdbc:postgresql://127.0.0.1:5432/devvault`, usuario y
contraseña en el entorno del lanzador. Flyway aplica las migraciones al iniciar.
El lanzador no administra ni detiene esta base externa.
