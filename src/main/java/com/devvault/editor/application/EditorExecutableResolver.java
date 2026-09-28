package com.devvault.editor.application;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Convierte un candidato de {@link EditorDescriptor#executables()} en una ruta
 * real de ejecutable, o dice que no lo encuentra.
 *
 * <p>Tres formas de candidato:
 *
 * <ol>
 *   <li><b>Nombre suelto</b> ({@code code}, {@code idea64}): se busca en cada
 *       carpeta del {@code PATH}, añadiendo las extensiones que usa Windows,
 *       porque {@code ProcessBuilder} no las completa solo como sí hace el
 *       intérprete de comandos.</li>
 *   <li><b>Ruta absoluta</b> ({@code %ProgramFiles%\...}): se comprueba
 *       directamente. Antes se expanden las variables de entorno con sintaxis de
 *       Windows, que es la que la gente reconoce.</li>
 *   <li><b>Ruta con comodín</b> ({@code ...\IntelliJ IDEA *\bin\idea64.exe}):
 *       necesario porque la carpeta de instalación de IntelliJ lleva la versión
 *       dentro del nombre.</li>
 * </ol>
 *
 * <p>Aquí no se ejecuta nada, solo se comprueba que el archivo exista. La
 * alternativa sería correr {@code <editor> --version}, que es lo que hace
 * {@code PhpPlugin.isPhpCliAvailable()} con PHP; pero allí el binario es de
 * consola y no tiene efectos secundarios. Lanzar un IDE gráfico para comprobar si existe
 * abriría una ventana en la pantalla del usuario cada vez que la UI pintara la
 * lista, así que no compensa. Que el archivo exista es una señal suficiente.
 */
@Component
public class EditorExecutableResolver {

    private static final Logger log = LoggerFactory.getLogger(EditorExecutableResolver.class);

    /** Extensiones que Windows prueba al invocar un comando sin extensión. */
    private static final List<String> WINDOWS_EXTENSIONS = List.of("", ".exe", ".cmd", ".bat", ".com");

    private static final Pattern ENV_VAR = Pattern.compile("%([^%]+)%");

    /**
     * @param candidate nombre suelto, ruta absoluta o ruta con comodines
     * @return ruta absoluta del ejecutable, o vacío si no está instalado
     */
    public Optional<Path> resolve(String candidate) {
        String expanded = expandEnvironmentVariables(candidate);
        try {
            if (expanded.indexOf('*') >= 0) {
                return newestGlobMatch(expanded);
            }
            Path path = Path.of(expanded);
            if (path.isAbsolute()) {
                return Files.isRegularFile(path) ? Optional.of(path.toAbsolutePath()) : Optional.empty();
            }
            return findOnPath(expanded);
        } catch (InvalidPathException e) {
            log.debug("Candidato de editor con ruta inválida '{}': {}", candidate, e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            // Un PATH raro o un carácter inesperado no debe impedir detectar
            // los demás editores.
            log.debug("No se pudo evaluar el candidato '{}': {}", candidate, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Busca en el {@code PATH} del proceso actual.
     *
     * <p>Delega en la variante que recibe el PATH como parámetro, que es la que
     * se puede probar sin manipurar el entorno del proceso: en los JDK
     * modernos el mapa de variables de entorno es inmutable y no hay forma
     * limpia de alterarlo desde un test.
     */
    Optional<Path> findOnPath(String command) {
        return findOnPath(command, System.getenv("PATH"));
    }

    Optional<Path> findOnPath(String command, String pathEnv) {
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }

        boolean windows = isWindows();
        for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            List<String> fileNames = windows
                    ? WINDOWS_EXTENSIONS.stream().map(ext -> command + ext).toList()
                    : List.of(command);
            for (String fileName : fileNames) {
                Path candidate = Path.of(dir, fileName);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate.toAbsolutePath());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resuelve el único segmento del patrón que contiene un {@code *}, elige la
     * coincidencia más reciente y continúa con los segmentos literales que
     * quedan. El comodín no cruza separadores, igual que en la terminal.
     *
     * <p>El troceado se hace sobre el texto y no con {@code Path.of}, porque
     * {@code *} es un carácter ilegal en una ruta de Windows: pasar el patrón
     * completo por {@code Path} lanzaría {@code InvalidPathException} antes de
     * llegar a la lógica del comodín.
     */
    private Optional<Path> newestGlobMatch(String pattern) {
        int wildcard = pattern.indexOf('*');
        if (wildcard < 0) {
            return Optional.empty();
        }

        // Se recorta el segmento que contiene el comodín, sin sus separadores.
        int segmentStart = wildcard;
        while (segmentStart > 0 && !isSeparator(pattern.charAt(segmentStart - 1))) {
            segmentStart--;
        }
        int segmentEnd = wildcard;
        while (segmentEnd < pattern.length() && !isSeparator(pattern.charAt(segmentEnd))) {
            segmentEnd++;
        }

        // La parte anterior al comodín es una ruta literal y válida.
        String head = pattern.substring(0, segmentStart);
        if (head.isBlank()) {
            return Optional.empty();
        }
        Path base = Path.of(head);
        if (!Files.isDirectory(base)) {
            return Optional.empty();
        }

        Pattern regex = globToRegex(pattern.substring(segmentStart, segmentEnd));
        if (regex == null) {
            return Optional.empty();
        }

        Optional<Path> chosen;
        try (var entries = Files.list(base)) {
            chosen = entries
                    .filter(Files::isDirectory)
                    .filter(dir -> regex.matcher(dir.getFileName().toString()).matches())
                    // Gana la carpeta más reciente, que en la práctica es la
                    // versión más nueva instalada.
                    .max(Comparator.comparing(EditorExecutableResolver::lastModified));
        } catch (IOException e) {
            log.debug("No se pudo listar '{}': {}", base, e.getMessage());
            return Optional.empty();
        }

        if (chosen.isEmpty()) {
            return Optional.empty();
        }

        // Los segmentos literales que quedan tras el comodín se resuelven uno a
        // uno para no arrastrar un separador inicial que interpretaría Path
        // como una ruta con raíz.
        Path full = chosen.get();
        for (String segment : pattern.substring(segmentEnd).split("[/\\\\]+")) {
            if (!segment.isBlank()) {
                full = full.resolve(segment);
            }
        }
        return Files.isRegularFile(full) ? Optional.of(full.toAbsolutePath()) : Optional.empty();
    }

    private boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }

    /** Traduce un segmento con {@code *} a su equivalente como expresión regular. */
    private Pattern globToRegex(String segment) {
        try {
            return Pattern.compile(Pattern.quote(segment).replace("*", "\\E.*\\Q"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Sustituye {@code %VAR%} por su valor. Una variable no definida se deja
     * intacta, para que el mensaje de diagnóstico muestre la ruta tal como
     * está escrita en el catálogo y no una ruta truncada.
     */
    private String expandEnvironmentVariables(String value) {
        Matcher matcher = ENV_VAR.matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            String replacement = System.getenv(matcher.group(1));
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(replacement == null ? matcher.group(0) : replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
