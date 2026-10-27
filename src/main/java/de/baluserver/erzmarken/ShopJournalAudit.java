package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only evidence inventory for manually reconciling shop operations.
 * This is NOT a substitute for verifying saved vanilla playerdata, tokens and warehouse.
 * The scanner never alters journal entries or marks a trade as completed.
 */
public final class ShopJournalAudit {
    public record Evidence(String filename, String sha256, long size, String status) { }
    public record Report(List<Evidence> entries, int pending, int suspicious, boolean blocksTrading) { }
    private ShopJournalAudit() { }

    public static Report inspect(Path worldRoot) throws IOException {
        return inspect(worldRoot, true);
    }

    /** Admission needs filenames/types/limits, not hashes of every archived receipt.
     * Never use this metadata-only report as recovery evidence.
     */
    static Report inspectForAdmission(Path worldRoot) throws IOException {
        return inspect(worldRoot, false);
    }

    private static Report inspect(Path worldRoot, boolean includeHashes) throws IOException {
        Path directory = worldRoot.resolve("data/erzmarken-shop-journal");
        Path absolute = directory.toAbsolutePath().normalize(), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Verlinkter Journalpfad");
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Journalpfad ist kein Verzeichnis");
        }
        if (!Files.isDirectory(directory)) return new Report(List.of(), 0, 0, false);
        List<Evidence> evidence = new ArrayList<>();
        Map<String, Integer> names = new HashMap<>();
        int pending = 0;
        int suspicious = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                    suspicious++;
                    evidence.add(new Evidence(file.getFileName().toString(), "-", -1, "UNEXPECTED_ENTRY"));
                    continue;
                }
                String name = file.getFileName().toString();
                boolean unresolved = name.endsWith(".pending.json");
                boolean archived = name.endsWith(".complete.json") || name.endsWith(".cancelled.json");
                String status = unresolved ? "UNRESOLVED_REVIEW_REQUIRED" : archived ? "ARCHIVED_UNVERIFIED" : "UNEXPECTED_FILE";
                if (unresolved) pending++;
                if (!unresolved && !archived) suspicious++;
                String id = name.replaceFirst("\\.(pending|complete|cancelled)\\.json$", "");
                if (names.merge(id, 1, Integer::sum) > 1) suspicious++;
                long size = Files.size(file);
                // Journal files are small; do not allow an unbounded memory allocation.
                if (size > 1024 * 1024) {
                    suspicious++;
                    evidence.add(new Evidence(name, "-", size, "OVERSIZED_FILE"));
                    continue;
                }
                String hash = includeHashes ? sha256(Files.readAllBytes(file)) : "-";
                evidence.add(new Evidence(name, hash, size, status));
            }
        }
        evidence.sort(Comparator.comparing(Evidence::filename));
        return new Report(List.copyOf(evidence), pending, suspicious, pending > 0 || suspicious > 0);
    }

    public static Path exportReport(Path worldRoot, Path destination) throws IOException {
        Report result = inspect(worldRoot);
        if (Files.exists(destination)) throw new IOException("Auditbericht existiert bereits; kein Ueberschreiben");
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        StringBuilder out = new StringBuilder("# Shop-Journal: schreibgeschuetzter Abgleich\n\n");
        out.append("Offen: ").append(result.pending()).append("; auffaellig: ")
           .append(result.suspicious()).append("; Ankaufsperre empfohlen: ").append(result.blocksTrading()).append("\n\n");
        out.append("WARNUNG: Hashes bestaetigen nur Dateiinhalte, NICHT die korrekte Buchung in Minecraft-Spielerdaten, Tokenkonto oder Warenlager. KEINE automatische Freigabe.\n\n");
        for (Evidence e : result.entries())
            out.append(e.filename()).append(" | ").append(e.status()).append(" | ")
               .append(e.size()).append(" bytes | SHA256 ").append(e.sha256()).append('\n');
        return Files.writeString(destination, out.toString(), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
