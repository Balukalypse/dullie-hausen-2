import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exercise the same Java archive path API Loom needs, without dependencies. */
class BuildEnvironmentProbe {
    public static void main(String[] args) throws Exception {
        Path scratch = null;
        Path archive = null;
        boolean failed = false;
        try {
            Path build = Path.of(args[0]).toAbsolutePath().normalize().resolve("build");
            Files.createDirectories(build);
            scratch = Files.createTempDirectory(build, "environment-probe-");
            archive = scratch.resolve("probe.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("probe.txt"));
                zip.write(new byte[]{1});
                zip.closeEntry();
            }
            archive.toRealPath();
            try (var fs = FileSystems.newFileSystem(java.net.URI.create("jar:" + archive.toUri()), Map.of())) {
                if (Files.readAllBytes(fs.getPath("/probe.txt"))[0] != 1)
                    throw new IOException("Archivinhalt weicht ab");
            }
            System.out.println("Java-Datei-/Archivzugriff: OK (kein Fabric-Build oder Server-Test)");
        } catch (IOException | RuntimeException failure) {
            failed = true;
            System.err.println("Java-Datei-/Archivzugriff fehlgeschlagen: " + failure);
            System.err.println("Loom benoetigt funktionierendes toRealPath/ZIP-Dateisystem. "
                + "Build in einer lokalen Java-/Windows-Umgebung mit diesem Zugriff ausfuehren.");
        } finally {
            if (archive != null) Files.deleteIfExists(archive);
            if (scratch != null) Files.deleteIfExists(scratch);
        }
        if (failed) System.exit(2);
    }
}
