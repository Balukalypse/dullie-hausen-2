package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Canonical location for mod-owned files. Does not migrate or delete world data. */
public final class CentralDataPaths {
    private static final List<String> DIRECTORIES = List.of(
        "config", "data", "bugreports", "bugreports/exports", "bugreports/ai-inbox",
        "bugreports/ai-outbox", "logs", "legacy-archive");
    private final Path root;

    private CentralDataPaths(Path root) { this.root = root; }

    public static CentralDataPaths prepare(Path serverRoot) throws IOException {
        Path server = serverRoot.toAbsolutePath().normalize();
        verifyNoLinks(server);
        Path base = server.resolve("dulliehausen");
        createSafeDirectory(base);
        for (String directory : DIRECTORIES) createSafeDirectory(base.resolve(directory));
        return new CentralDataPaths(base);
    }

    public Path root() { return root; }
    public Path config(String filename) throws IOException { return file("config", filename); }
    public Path data(String filename) throws IOException { return file("data", filename); }
    public Path export(String filename) throws IOException { return file("bugreports/exports", filename); }
    public Path aiInbox(String filename) throws IOException { return file("bugreports/ai-inbox", filename); }
    public Path aiOutbox(String filename) throws IOException { return file("bugreports/ai-outbox", filename); }

    private Path file(String subfolder, String filename) throws IOException {
        if (filename == null || !filename.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}") || filename.equals(".."))
            throw new IOException("Ungueltiger Mod-Dateiname");
        Path path = root.resolve(subfolder).resolve(filename).normalize();
        if (!path.startsWith(root)) throw new IOException("Pfad ausserhalb des Mod-Ordners");
        verifyNoLinks(path);
        return path;
    }

    private static void createSafeDirectory(Path path) throws IOException {
        verifyNoLinks(path);
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Kein Verzeichnis: " + path);
    }

    private static void verifyNoLinks(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolischer Link im Mod-Pfad: " + current);
        }
    }
}
