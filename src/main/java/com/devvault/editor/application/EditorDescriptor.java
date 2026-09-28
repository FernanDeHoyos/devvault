package com.devvault.editor.application;

import java.util.List;

/**
 * Ficha de un editor que DevVault sabe abrir.
 *
 * <p>Es un catálogo <strong>cerrado</strong> y compilado, no algo que el usuario
 * pueda ampliar con texto libre. La razón es de seguridad: este módulo ejecuta
 * un binario en la máquina del usuario, así que aceptar
 * {@code {"command": "lo que sea"}} convertiría el endpoint en ejecución
 * remota de comandos. Lo único entre ese endpoint y el sistema es que la API
 * está en loopback y hay JWT, y no conviene ampliar esa frontera.
 *
 * <p>{@code executables} es una lista de candidatos en orden de preferencia, no
 * un comando único, porque cada editor se instala de forma distinta:
 *
 * <ul>
 *   <li>{@code code} — si el usuario lo puso en el PATH, basta con el nombre.</li>
 *   <li>{@code %LOCALAPPDATA%\Programs\Microsoft VS Code\Code.exe} — ruta
 *       absoluta con variables de entorno, para cuando no está en el PATH.</li>
 *   <li>{@code %ProgramFiles%\JetBrains\IntelliJ IDEA *\bin\idea64.exe} — la
 *       carpeta de instalación lleva la versión dentro, así que hace falta un
 *       comodín.</li>
 * </ul>
 *
 * @param id          identificador estable, el que viaja en la API
 * @param displayName nombre para mostrar en la UI
 * @param executables candidatos a resolver, en orden de preferencia
 */
public record EditorDescriptor(String id, String displayName, List<String> executables) {

    public EditorDescriptor {
        executables = List.copyOf(executables);
    }

    /**
     * Un editor de terminal (Neovim, Vim) no puede abrirse con
     * {@code ProcessBuilder}: sin consola adjunta muere de inmediato. No están
     * en el catálogo todavía, y no se añadirán sin decidir antes cómo se les
     * abre una ventana de terminal de forma fiable en cada sistema operativo.
     */
    public boolean isTerminalBased() {
        return false;
    }
}
