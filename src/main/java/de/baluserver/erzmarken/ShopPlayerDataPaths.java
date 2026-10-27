package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Offline evidence paths: current 26.3 layout or an unambiguous legacy copy. */
public final class ShopPlayerDataPaths {
    private ShopPlayerDataPaths() { }
    public static Path resolveOffline(Path world, UUID player) throws IOException {
        Path root = world.toAbsolutePath().normalize();
        Path modern = root.resolve("players/data").resolve(player + ".dat");
        Path legacy = root.resolve("playerdata").resolve(player + ".dat");
        checkPath(modern); checkPath(legacy);
        boolean hasModern = Files.exists(modern, LinkOption.NOFOLLOW_LINKS);
        boolean hasLegacy = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        if (hasModern && hasLegacy) throw new IOException("Mehrere Spielerdateien: aktuellen Stand manuell bestimmen");
        Path chosen = hasModern ? modern : legacy;
        // A current-layout directory without this player must not fall back to a stale legacy file.
        if (!hasModern && Files.exists(root.resolve("players/data"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Aktuelles Spielerdateiverzeichnis vorhanden, aber Spielerdatei fehlt");
        if (!Files.isRegularFile(chosen, LinkOption.NOFOLLOW_LINKS) || Files.size(chosen) == 0)
            throw new IOException("Spielerdatei fehlt, ist leer oder keine regulaere Datei");
        return chosen;
    }
    private static void checkPath(Path path) throws IOException {
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Verlinkter Spielerdateipfad");
            if (!cursor.equals(path) && Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Spielerdateipfad enthaelt Datei statt Verzeichnis");
        }
    }
}
