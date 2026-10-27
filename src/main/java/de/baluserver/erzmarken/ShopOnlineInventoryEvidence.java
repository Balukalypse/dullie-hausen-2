package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Evidence from live server memory, NOT proof that playerdata has been saved to disk.
 * Capture must be invoked on the Minecraft server thread; this class deliberately
 * knows nothing about Minecraft and never commits, grants or removes items/tokens.
 */
public final class ShopOnlineInventoryEvidence {
    private static final int MAX_SNAPSHOT = 8 * 1024 * 1024;
    public enum Moment { BEFORE, AFTER }
    @FunctionalInterface public interface SnapshotSource { byte[] snapshot() throws IOException; }
    public record Receipt(UUID transaction, UUID player, Moment moment, String sha256, int bytes,
                          boolean durableInventoryCommitConfirmed) { }
    private ShopOnlineInventoryEvidence() { }

    public static Receipt capture(Path world, UUID transaction, UUID player, Moment moment,
                                  SnapshotSource source) throws IOException {
        if (world == null || transaction == null || player == null || moment == null || source == null)
            throw new IOException("Missing evidence parameters");
        Path journal = world.resolve("data/erzmarken-shop-journal")
                .resolve(transaction + ".pending.json");
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(journal))
            throw new IOException("Transaction must be pending");
        Path folder = world.resolve("data/erzmarken-shop-live-evidence").resolve(transaction.toString());
        Files.createDirectories(folder);
        Path dataFile = folder.resolve(moment.name().toLowerCase() + ".inventory.bin");
        Path manifest = folder.resolve(moment.name().toLowerCase() + ".manifest.txt");
        if (Files.exists(dataFile) || Files.exists(manifest))
            throw new FileAlreadyExistsException("Evidence exists for " + transaction + ": " + moment);
        byte[] bytes = source.snapshot();
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SNAPSHOT)
            throw new IOException("Invalid inventory snapshot length");
        String sha = sha256(bytes);
        // Two files are not atomically committed together; only manifest publication
        // makes an evidence pair visible to verification. Leftover data stays blocked.
        try (var out = Files.newOutputStream(dataFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(bytes);
        }
        String body = "format=1\ntransaction=" + transaction + "\nplayer=" + player
                + "\nmoment=" + moment + "\nsource=LIVE_MEMORY_UNCONFIRMED\n"
                + "durable_inventory_commit_confirmed=false\nbytes=" + bytes.length + "\nsha256=" + sha + "\n";
        try {
            Files.writeString(manifest, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            // Fail closed: never clear the evidence data on ambiguous failure.
            throw e;
        }
        return new Receipt(transaction, player, moment, sha, bytes.length, false);
    }

    public static boolean verify(Path world, UUID transaction, UUID player, Moment moment) throws IOException {
        if (world == null || transaction == null || player == null || moment == null) return false;
        Path dir = world.resolve("data/erzmarken-shop-live-evidence").resolve(transaction.toString());
        Path file = dir.resolve(moment.name().toLowerCase() + ".inventory.bin");
        Path manifest = dir.resolve(moment.name().toLowerCase() + ".manifest.txt");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || Files.isSymbolicLink(manifest)
                || Files.size(file) > MAX_SNAPSHOT) return false;
        String[] rows = Files.readString(manifest, StandardCharsets.UTF_8).split("\\n", -1);
        if (rows.length != 9 || !rows[0].equals("format=1")
                || !rows[1].equals("transaction=" + transaction)
                || !rows[2].equals("player=" + player)
                || !rows[3].equals("moment=" + moment)
                || !rows[4].equals("source=LIVE_MEMORY_UNCONFIRMED")
                || !rows[5].equals("durable_inventory_commit_confirmed=false")
                || !rows[8].isEmpty()) return false;
        try {
            return Long.parseLong(rows[6].substring("bytes=".length())) == Files.size(file)
                    && rows[6].startsWith("bytes=") && rows[7].equals("sha256=" + sha256(Files.readAllBytes(file)));
        } catch (RuntimeException e) { return false; }
    }
    private static String sha256(byte[] value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 255));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
