# Contribuir a DevVault

Gracias por considerar una contribución. Para cambios grandes, abre primero
una issue para acordar el alcance y evitar trabajo duplicado.

## Desarrollo local

DevVault requiere Java 17 para el backend. La interfaz está en el submódulo
`ui/` y requiere Node.js `^20.19` o `>=22.12` (Vite 8).

```powershell
git clone --recurse-submodules https://github.com/FernanDeHoyos/devvault.git
cd devvault
.\gradlew.bat test
cd ui
npm ci
npm run lint
npm run build
```

En Linux, usa `./gradlew test` en lugar de `.\gradlew.bat test`.
Las pruebas de integración pueden necesitar Docker para Testcontainers.

## Cambios en la interfaz

`ui/` es un repositorio Git independiente. Publica primero el commit de la UI
y su `dist/`; luego actualiza y publica el puntero del submódulo en el backend.
El workflow de GitHub Actions construye y prueba ambos repositorios en Windows
y Ubuntu.

## Pull requests

- Explica el problema y el comportamiento esperado; incluye pasos de
  reproducción o capturas cuando sean útiles.
- Añade o actualiza pruebas para el comportamiento cambiado.
- Ejecuta las pruebas del backend y, si tocaste la UI, `npm run lint` y
  `npm run build`.
- Mantén cada cambio enfocado y no incluyas credenciales, datos personales,
  artefactos de compilación ni cambios no relacionados.
- Indica en la descripción qué comandos ejecutaste y sus resultados.

Al contribuir, aceptas que tu contribución se distribuya bajo Apache License
2.0, la licencia de este proyecto.
