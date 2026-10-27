package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable, per-transaction disk evidence. Never commits or repairs a transaction. */
public final class ShopTransactionEvidenceStore {
    private static final long MAX_FILE = 64L * 1024 * 1024;
    public enum Moment { BEFORE, AFTER }
    public enum Source { PLAYERDATA, PLAYER_TOKENS, SHOP_REGISTER, SHOP_WAREHOUSE }
    public record Finding(String source, String sha256, long bytes) { }
    public record Result(String transactionId, Moment moment, List<Finding> files) { }
    private ShopTransactionEvidenceStore() { }

    /** Captures independently persisted files; caller must ensure consistent, quiescent snapshots. */
    public static Result capture(Path worldRoot, String transactionId, Moment moment,
                                 Map<Source, Path> sources) throws IOException {
        if (transactionId == null || !transactionId.matches("[0-9a-fA-F-]{36}"))
            throw new IOException("Invalid transaction id");
        if (moment == null || sources == null || sources.size() != Source.values().length
                || !sources.keySet().containsAll(EnumSet.allOf(Source.class)))
            throw new IOException("Four independent source paths required");
        Path root = worldRoot.resolve("data/erzmarken-shop-journal");
        Path journal = root.resolve(transactionId + ".pending.json");
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Transaction must exist and remain pending");
        Path evidenceRoot = worldRoot.resolve("data/erzmarken-shop-evidence");
        Files.createDirectories(evidenceRoot);
        Path finalDir = evidenceRoot.resolve(transactionId).resolve(moment.name().toLowerCase(Locale.ROOT));
        if (Files.exists(finalDir)) throw new FileAlreadyExistsException(finalDir.toString());
        Path txnDir = evidenceRoot.resolve(transactionId);
        Files.createDirectories(txnDir);
        Path temp = Files.createTempDirectory(txnDir, ".capture-");
        boolean published = false;
        try {
            List<Finding> findings = new ArrayList<>();
            for (Source source : Source.values()) {
                Path original = sources.get(source);
                if (original == null || Files.isSymbolicLink(original)
                    || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Missing/unsafe source: " + source);
                if (Files.size(original) > MAX_FILE) throw new IOException("Source too large: " + source);
                Path copy = temp.resolve(source.name().toLowerCase(Locale.ROOT) + ".bin");
                Files.copy(original, copy);
                if (Files.size(original) != Files.size(copy) ||
                    !MessageDigest.isEqual(hash(original), hash(copy)))
                    throw new IOException("Source changed during capture: " + source);
                findings.add(new Finding(source.name(), hex(hash(copy)), Files.size(copy)));
            }
            StringBuilder manifest = new StringBuilder("transaction=" + transactionId + "\nmoment=" + moment + "\n");
            for (Finding f : findings) manifest.append(f.source()).append('|').append(f.bytes())
                    .append('|').append(f.sha256()).append('\n');
            Files.writeString(temp.resolve("manifest.txt"), manifest.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            // Publish only after all copies + manifest validate; never overwrite evidence.
            try { Files.move(temp, finalDir, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, finalDir); }
            published = true;
            return new Result(transactionId, moment, List.copyOf(findings));
        } finally { if (!published) deleteTemp(temp); }
    }

    /** Checks archived bytes against their recorded SHA-256; no edits or automatic approvals. */
    public static boolean verify(Path worldRoot, String transactionId, Moment moment) throws IOException {
        if (transactionId == null || !transactionId.matches("[0-9a-fA-F-]{36}") || moment == null)
            throw new IOException("Invalid evidence identifier");
        Path dir = worldRoot.resolve("data/erzmarken-shop-evidence").resolve(transactionId)
                .resolve(moment.name().toLowerCase(Locale.ROOT));
        Path manifest = dir.resolve("manifest.txt");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return false;
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.size() != 6 || !lines.get(0).equals("transaction=" + transactionId)
                || !lines.get(1).equals("moment=" + moment)) return false;
        for (int i = 0; i < Source.values().length; i++) {
            String[] parts = lines.get(i+2).split("\\|", -1);
            if (parts.length != 3 || !parts[0].equals(Source.values()[i].name())) return false;
            Path file = dir.resolve(parts[0].toLowerCase(Locale.ROOT) + ".bin");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
            if (Files.size(file) != Long.parseLong(parts[1]) || !hex(hash(file)).equals(parts[2])) return false;
        }
        return true;
    }
    private static byte[] hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = stream.read(buf)) != -1) digest.update(buf, 0, n);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static void deleteTemp(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var files = Files.list(dir)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(dir);
    }
}
