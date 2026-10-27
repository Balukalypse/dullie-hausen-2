package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Durable inventory snapshot staging, independent of Minecraft APIs.
 * Payloads are complete, externally serialized inventory snapshots; this class
 * does NOT serialize items or switch inventories. NO automatic rollback of live
 * player state is attempted. Any interrupted switch MUST fail closed.
 */
public final class ShopInventoryVault {
    public enum Phase { PRIVATE_ACTIVE, ENTER_PREPARED, SHOP_ACTIVE, EXIT_PREPARED, RECOVERY_REQUIRED }
    public record Record(Phase phase, byte[] privateData, byte[] shopData) {
        public Record {
            privateData = privateData == null ? null : privateData.clone();
            shopData = shopData == null ? null : shopData.clone();
        }
        @Override public byte[] privateData() { return privateData == null ? null : privateData.clone(); }
        @Override public byte[] shopData() { return shopData == null ? null : shopData.clone(); }
    }
    private static final long MAX_VAULT_BYTES = 4L * 1024 * 1024;
    private static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;
    private final Path root;
    public ShopInventoryVault(Path root) { this.root = root; }

    /** A seller's live items must not belong to an active or unresolved shop session.
     * Consult persisted state, independent of current ownership or feature admission.
     * Read-only: no guessed restoration or quarantine rewrite on this preflight.
     */
    public synchronized void requirePrivateActive(UUID playerId) throws IOException {
        if (read(playerId).phase() != Phase.PRIVATE_ACTIVE)
            throw new IOException("Privathandel gesperrt: Shop-Arbeitsinventar zuerst beenden oder pruefen lassen");
    }

    /** UUID-based paths only; no usernames or user-supplied path segments. */
    public synchronized Record read(UUID playerId) throws IOException {
        checkRoot(false);
        Path file = location(playerId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Record(Phase.PRIVATE_ACTIVE, null, null);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > MAX_VAULT_BYTES)
            throw new IOException("Unsafe or oversized inventory vault: " + playerId);
        String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\\n", -1);
        if (lines.length != 5 || !"DULLIE_VAULT_1".equals(lines[0]))
            throw new IOException("Invalid inventory vault envelope: " + playerId);
        String signed = String.join("\n", lines[0], lines[1], lines[2], lines[3]);
        CRC32 crc = new CRC32();
        crc.update(signed.getBytes(StandardCharsets.UTF_8));
        if (!Long.toHexString(crc.getValue()).equals(lines[4].trim()))
            throw new IOException("Inventory vault checksum mismatch: " + playerId);
        try {
            Phase phase = Phase.valueOf(lines[1]);
            byte[] priv = decode(lines[2]);
            byte[] shop = decode(lines[3]);
            if ((priv != null && priv.length > MAX_SNAPSHOT_BYTES) || (shop != null && shop.length > MAX_SNAPSHOT_BYTES))
                throw new IOException("Inventory snapshot exceeds safety limit: " + playerId);
            // A valid checksum does not establish a valid state-machine record.
            // Empty payloads and contradictory phases must never pass the login gate.
            if ((priv != null && priv.length == 0) || (shop != null && shop.length == 0))
                throw new IOException("Empty inventory vault payload: " + playerId);
            if (phase == Phase.PRIVATE_ACTIVE && priv != null)
                throw new IOException("Private phase still contains unrestored private backup: " + playerId);
            if (phase != Phase.PRIVATE_ACTIVE && priv == null)
                throw new IOException("Missing private backup for active shop session");
            if (phase == Phase.EXIT_PREPARED && shop == null)
                throw new IOException("Missing shop backup for prepared exit: " + playerId);
            return new Record(phase, priv, shop);
        } catch (IllegalArgumentException e) {
            throw new IOException("Corrupt inventory vault state: " + playerId, e);
        }
    }

    /**
     * Operator diagnostics only: enumerate inventory sessions that cannot safely be
     * treated as ordinary private inventory. Includes apparently active shops.
     * A corrupt vault is reported rather than skipped; this does not recover items.
     */
    public record Inspection(UUID playerId, Phase phase, String error) { }

    public synchronized List<Inspection> inspectUnfinished() throws IOException {
        checkRoot(false);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Inspection> results = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                String filename = file.getFileName().toString();
                if (!filename.endsWith(".vault")) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unsafe inventory vault entry: " + filename);
                String prefix = filename.substring(0, filename.length() - 6);
                UUID id;
                try { id = UUID.fromString(prefix); }
                catch (IllegalArgumentException ex) {
                    throw new IOException("Unexpected vault filename: " + filename, ex);
                }
                // UUID.fromString also accepts shortened groups and uppercase.
                // read(id) uses the canonical name and would otherwise inspect a different file.
                if (!id.toString().equals(prefix))
                    throw new IOException("Noncanonical vault filename: " + filename);
                try {
                    Record record = read(id);
                    if (record.phase() != Phase.PRIVATE_ACTIVE)
                        results.add(new Inspection(id, record.phase(), null));
                } catch (IOException ex) {
                    results.add(new Inspection(id, null, ex.getMessage()));
                }
            }
        }
        results.sort((a,b) -> a.playerId().compareTo(b.playerId()));
        return List.copyOf(results);
    }

    /** Stage 1: private inventory MUST be backed up before live inventory changes. */
    public synchronized void prepareEnter(UUID id, byte[] fullPrivateSnapshot) throws IOException {
        if (fullPrivateSnapshot != null && fullPrivateSnapshot.length > MAX_SNAPSHOT_BYTES)
            throw new IOException("Private snapshot exceeds limit");
        if (fullPrivateSnapshot == null || fullPrivateSnapshot.length == 0)
            throw new IllegalArgumentException("Empty private inventory snapshot");
        Record r = read(id);
        if (r.phase() != Phase.PRIVATE_ACTIVE) throw new IllegalStateException("Session not closed");
        write(id, new Record(Phase.ENTER_PREPARED, fullPrivateSnapshot, r.shopData()));
    }

    /** Stage 2: caller must verify successful loading of the shop inventory. */
    public synchronized void confirmEnter(UUID id) throws IOException {
        Record r = require(id, Phase.ENTER_PREPARED);
        write(id, new Record(Phase.SHOP_ACTIVE, r.privateData(), r.shopData()));
    }

    /** Stage 3: shop inventory MUST be snapshotted before private restore. */
    public synchronized void prepareExit(UUID id, byte[] fullShopSnapshot) throws IOException {
        if (fullShopSnapshot != null && fullShopSnapshot.length > MAX_SNAPSHOT_BYTES)
            throw new IOException("Shop snapshot exceeds limit");
        if (fullShopSnapshot == null || fullShopSnapshot.length == 0)
            throw new IllegalArgumentException("Empty shop inventory snapshot");
        Record r = require(id, Phase.SHOP_ACTIVE);
        write(id, new Record(Phase.EXIT_PREPARED, r.privateData(), fullShopSnapshot));
    }

    /** Stage 4: caller must verify successful private inventory restoration. */
    public synchronized void confirmExit(UUID id) throws IOException {
        Record r = require(id, Phase.EXIT_PREPARED);
        write(id, new Record(Phase.PRIVATE_ACTIVE, null, r.shopData()));
    }

    /** Explicit administrator intervention; never silently replay an interrupted switch. */
    public synchronized void flagRecovery(UUID id) throws IOException {
        Record r = read(id);
        if (r.phase() == Phase.PRIVATE_ACTIVE) return;
        write(id, new Record(Phase.RECOVERY_REQUIRED, r.privateData(), r.shopData()));
    }

    private Record require(UUID id, Phase phase) throws IOException {
        Record r = read(id);
        if (r.phase() != phase) throw new IllegalStateException("Expected " + phase + ", got " + r.phase());
        return r;
    }
    private Path location(UUID id) { return root.resolve(id.toString() + ".vault"); }
    private static String encode(byte[] b) { return b == null ? "-" : Base64.getEncoder().encodeToString(b); }
    private static byte[] decode(String s) { return "-".equals(s) ? null : Base64.getDecoder().decode(s); }
    private void checkRoot(boolean create) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Path cursor = normalized.getRoot();
        for (Path part : normalized) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(cursor))
                throw new IOException("Inventory vault path contains symbolic link: " + cursor);
        }
        if (create) Files.createDirectories(root);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Inventory vault root is not a directory");
    }
    private void write(UUID id, Record r) throws IOException {
        checkRoot(true);
        Path existing = location(id);
        if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe inventory vault destination: " + id);
        Path target = location(id);
        if ((r.privateData() != null && r.privateData().length > MAX_SNAPSHOT_BYTES)
            || (r.shopData() != null && r.shopData().length > MAX_SNAPSHOT_BYTES))
            throw new IOException("Inventory snapshot exceeds limit");
        Path temp = Files.createTempFile(root, id + "-", ".tmp");
        String signed = String.join("\n", "DULLIE_VAULT_1", r.phase().name(),
                encode(r.privateData()), encode(r.shopData()));
        CRC32 crc = new CRC32();
        crc.update(signed.getBytes(StandardCharsets.UTF_8));
        byte[] output = (signed + "\n" + Long.toHexString(crc.getValue())).getBytes(StandardCharsets.UTF_8);
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(output);
                while (buffer.hasRemaining()) ch.write(buffer);
                ch.force(true);
            }
            // Fail closed if atomic move is unavailable: do not fall back to torn writes.
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { throw new IOException("Atomic move unsupported", ex); }
        } finally { Files.deleteIfExists(temp); }
    }
}
