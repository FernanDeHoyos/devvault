package com.devvault.discovery.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Bounded file access shared by architecture parsers. */
final class ProjectFileReader {
    private ProjectFileReader() {}
    static boolean withinLimit(Path file, long limit, String label, List<String> warnings) {
        try {
            if (Files.size(file) <= limit) return true;
            warnings.add("Se omitió " + label + " porque supera el tamaño máximo de lectura.");
        } catch (IOException e) { warnings.add("No se pudo leer " + label + "."); }
        return false;
    }
    static String read(Path file, long limit, String label, List<String> warnings) {
        if (!withinLimit(file, limit, label, warnings)) return "";
        try { return Files.readString(file); }
        catch (IOException e) { warnings.add("No se pudo leer " + label + "."); return ""; }
    }
}
