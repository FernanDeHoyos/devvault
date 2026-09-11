package com.devvault.discovery.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Lee rama activa y hash corto del último commit directamente de la carpeta
 * .git, sin invocar el binario `git` ni depender de JGit (HU-10). Suficiente
 * para lo que pide el MVP: no parsea objetos de commit (mensaje, fecha),
 * solo referencias — que es información de solo texto plano en .git/refs.
 */
@Component
public class GitInfoReader {
    
    public record GitInfo(String branch, String shortCommitHash) {}

    /**
     * Lee la información de Git del proyecto.
     *
     * @param projectPath la ruta del proyecto
     * @return la información de Git o un Optional vacío si no se encuentra
     */
    public Optional<GitInfo> read(Path projectPath) {
        Path gitDir = projectPath.resolve(".git");

         System.out.println("PROJECT PATH: " + projectPath);
    System.out.println("GIT DIR: " + gitDir);
    System.out.println("GIT EXISTS: " + Files.exists(gitDir));
    System.out.println("GIT IS DIRECTORY: " + Files.isDirectory(gitDir));
    
        if (!Files.exists(gitDir) || !Files.isDirectory(gitDir)) {
            return Optional.empty();
        }

        // Intentar leer la rama activa y el hash del último commit
        try {
            // Leer la rama activa desde .git/HEAD
            Path headFile = gitDir.resolve("HEAD");
             System.out.println("HEAD FILE: " + headFile);
        System.out.println("HEAD EXISTS: " + Files.exists(headFile));
            String head = Files.readString(headFile).trim();
            //String head = Files.readString(gitDir.resolve("HEAD")).trim();

  System.out.println("HEAD CONTENT: " + head);
            if (head.startsWith("ref:")) {
                String refPath = head.substring("ref:".length()).trim();
                 System.out.println("REF PATH: " + refPath);
                String branch = refPath.substring(refPath.lastIndexOf('/') + 1);
                String commitHash = resolveRef(gitDir, refPath).orElse("sin commits todavía");
                System.out.println("BRANCH: " + branch);
            System.out.println("COMMIT: " + commitHash);
                return Optional.of(new GitInfo(branch, shorten(commitHash)));
            }

            
            // HEAD detached: el archivo contiene el SHA directamente, no una referencia
            return Optional.of(new GitInfo("(detached)", shorten(head)));

        } catch (IOException e) {
            throw new RuntimeException("Error al leer información de Git en " + projectPath, e);
        }
    }

    /**
     * Resuelve una referencia a un hash de commit.
     *
     * @param gitDir la ruta del directorio .git
     * @param refPath la ruta de la referencia
     * @return el hash del commit o un Optional vacío si no se encuentra
     */
    private Optional<String> resolveRef(Path gitDir, String refPath) throws IOException {
        Path refFile = gitDir.resolve(refPath);
        if (Files.exists(refFile)) {
            return Optional.of(Files.readString(refFile).trim());
        }
        // La rama puede estar "empaquetada" (packed-refs) en vez de tener su propio archivo
        return readFromPackedRefs(gitDir, refPath);
    }

    /**
     * Lee el hash de commit desde el archivo packed-refs si la referencia está empaquetada.
     *
     * @param gitDir la ruta del directorio .git
     * @param refPath la ruta de la referencia
     * @return el hash del commit o un Optional vacío si no se encuentra
     */
    private Optional<String> readFromPackedRefs(Path gitDir, String refPath) throws IOException {
        Path packedRefsFile = gitDir.resolve("packed-refs");
        if (!Files.exists(packedRefsFile)) {
            return Optional.empty();
        }

        return Files.lines(packedRefsFile)
                .filter(line -> !line.startsWith("#") && !line.startsWith("^"))
                .map(line -> line.split(" "))
                .filter(parts -> parts.length == 2 && parts[1].equals(refPath))
                .map(parts -> parts[0])
                .findFirst();
    }

    /**
     * Acorta un hash de commit a los primeros 7 caracteres.
     *
     * @param commitHash el hash completo del commit
     * @return el hash acortado
     */
    private String shorten(String commitHash) {
        return commitHash.length() > 7 ? commitHash.substring(0, 7) : commitHash;
    }
}
