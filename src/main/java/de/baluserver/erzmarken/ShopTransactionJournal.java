package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Durable per-transaction audit. This is NOT a cross-file atomic transaction.
 * Unresolved states must be reconciled by an administrator, never auto-replayed.
 * Call only on the Minecraft server thread.
 */
public final class ShopTransactionJournal {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path folder;

    public record Entry(String id, String playerId, String itemId, int quantity,
                        long total, String stage, long createdAt) { }

    private ShopTransactionJournal() { }

    public static void initialize(Path worldRoot) throws IOException {
        folder = worldRoot.resolve("data/erzmarken-shop-journal");
        Files.createDirectories(folder);
    }

    public static void ensureClear() throws IOException {
        if (folder == null) throw new IOException("Transaktionsjournal nicht initialisiert");
        ShopRetailJournal.requireClear(folder.getParent().getParent());
        ShopJournalAudit.Report audit = ShopJournalAudit.inspect(folder.getParent().getParent());
        if (audit.blocksTrading())
            throw new IOException("Shop-Journal ungeklärt: " + audit.pending()
                + " offene Buchungen, " + audit.suspicious()
                + " auffaellige Eintraege. Ankaufsperre bis zur manuellen Pruefung.");
        Path world = folder.getParent().getParent();
        if (!audit.entries().isEmpty()
                || Files.exists(world.resolve("data/erzmarken.json"), LinkOption.NOFOLLOW_LINKS)) {
            var tokenAudit = ShopTokenReceiptAudit.inspect(world, audit, 1);
            if (tokenAudit.reviewRequired())
                throw new IOException("Persistierte Shop-Tokenbelege ungeklaert; /shop journal pruefen. Keine Ankaufsfreigabe.");
        }
    }

    static void requireWorld(Path world) throws IOException {
        if (folder == null || !folder.toAbsolutePath().normalize().equals(
                world.toAbsolutePath().normalize().resolve("data/erzmarken-shop-journal")))
            throw new IOException("Journal gehoert nicht zu dieser Welt");
    }

    static void requirePending(Entry expected) throws IOException {
        Entry disk = loadPending(expected.id());
        if (!expected.equals(disk)) throw new IOException("Buchung entspricht nicht dem offenen Journalstand");
    }

    /** Links immutable on-disk evidence to a journal ID, without changing journal stage. */
    public static ShopTransactionEvidenceStore.Result captureEvidence(
            Entry entry, ShopTransactionEvidenceStore.Moment moment,
            java.util.Map<ShopTransactionEvidenceStore.Source, Path> sourceFiles) throws IOException {
        if (folder == null) throw new IOException("Journal not initialized");
        if (!Files.isRegularFile(path(entry.id()), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Transaction not pending");
        return ShopTransactionEvidenceStore.capture(folder.getParent().getParent(), entry.id(), moment, sourceFiles);
    }

    public static Entry begin(UUID player, String itemId, int quantity, long total) throws IOException {
        if (player == null) throw new IOException("Spieler der Buchung fehlt");
        Entry entry = new Entry(UUID.randomUUID().toString(), player.toString(), itemId,
            quantity, total, "PREPARED", System.currentTimeMillis());
        requireValidEntry(entry);
        ensureClear();
        if (Files.exists(path(entry.id()))) throw new IOException("Journal-ID existiert bereits");
        store(entry, true);
        return entry;
    }

    public static Entry advance(Entry previous, String stage) throws IOException {
        Entry disk = loadPending(previous.id());
        if (!ShopJournalTransitionGuard.sameImmutableData(
                previous.id(), previous.playerId(), previous.itemId(), previous.quantity(),
                previous.total(), previous.createdAt(), disk.id(), disk.playerId(),
                disk.itemId(), disk.quantity(), disk.total(), disk.createdAt()))
            throw new IOException("Transaktionsdaten widersprechen gespeicherter Buchung: " + previous.id());
        if (!ShopJournalTransitionGuard.mayAdvance(previous.id(), previous.stage(),
                disk.id(), disk.stage(), stage))
            throw new IOException("Veralteter, falscher oder ungueltiger Journal-Uebergang: "
                + previous.stage() + " -> " + stage + "; gespeichert=" + disk.stage());
        Entry updated = new Entry(previous.id(), previous.playerId(), previous.itemId(),
            previous.quantity(), previous.total(), stage, previous.createdAt());
        store(updated, false);
        return updated;
    }

    public static void finish(Entry entry) throws IOException {
        // Only call after external, independently verified persistence of playerdata,
        // token store and warehouse. Never call merely after ItemStack.shrink().
        Path completed = folder.resolve(entry.id() + ".complete.json");
        if (Files.exists(completed, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Beleg bereits abgeschlossen");
        Entry done = advance(entry, "COMPLETED_VERIFIED");
        Path pending = path(done.id());
        Path complete = folder.resolve(done.id() + ".complete.json");
        archive(pending, complete);
    }

    /** Only after explicit operator confirmation and fresh external readback; never replay a payment. */
    static void finishReviewed(Entry entry) throws IOException {
        requirePending(entry);
        if ("ITEM_REMOVED_UNSAVED".equals(entry.stage())) { finish(entry); return; }
        if (!"COMPLETED_VERIFIED".equals(entry.stage())) throw new IOException("Recovery-Abschluss aus dieser Phase verweigert");
        archive(path(entry.id()), folder.resolve(entry.id() + ".complete.json"));
    }

    /** Only for a confirmed rollback in which neither side retains any changes. */
    public static void cancel(Entry entry) throws IOException {
        Entry cancelled = advance(entry, "CANCELLED");
        archive(path(cancelled.id()), folder.resolve(cancelled.id() + ".cancelled.json"));
    }

    private static Path path(String id) { return folder.resolve(id + ".pending.json"); }

    private static Entry loadPending(String id) throws IOException {
        if (folder == null) throw new IOException("Journal nicht initialisiert");
        return readPendingForReview(folder.getParent().getParent(), id);
    }

    /** Read-only access for offline/admin comparison; does not change static journal context. */
    public static Entry readPendingForReview(Path world, String id) throws IOException {
        try { ShopBuybackReceiptLink.requireValidTransactionId(id); }
        catch (IllegalArgumentException failure) { throw new IOException("Ungueltige Journal-ID", failure); }
        Path file = world.resolve("data/erzmarken-shop-journal").resolve(id + ".pending.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || Files.size(file) > 1024 * 1024)
            throw new IOException("Journal fehlt, ist verlinkt oder unplausibel: " + id);
        return decodeForReview(StrictJsonFiles.readObject(file, 1024 * 1024), id);
    }

    /** Shared strict schema for pending records and read-only archived comparisons. */
    static Entry decodeForReview(com.google.gson.JsonObject raw, String id) throws IOException {
        try {
            for (String field : new String[]{"id", "playerId", "itemId", "stage"})
                if (!raw.has(field) || !raw.get(field).isJsonPrimitive() || !raw.getAsJsonPrimitive(field).isString())
                    throw new IOException("Journal-Textfeld fehlt oder hat falschen Typ: " + field);
            for (String field : new String[]{"quantity", "total", "createdAt"})
                if (!raw.has(field) || !raw.get(field).isJsonPrimitive() || !raw.getAsJsonPrimitive(field).isNumber())
                    throw new IOException("Journal-Zahlfeld fehlt oder hat falschen Typ: " + field);
            raw.get("quantity").getAsBigDecimal().intValueExact();
            raw.get("total").getAsBigDecimal().longValueExact();
            raw.get("createdAt").getAsBigDecimal().longValueExact();
            Entry entry = GSON.fromJson(raw, Entry.class);
            requireValidEntry(entry);
            if (!id.equals(entry.id())) throw new IOException("Journal-ID widerspricht Dateiname");
            return entry;
        }
        catch (RuntimeException ex) { throw new IOException("Journal unlesbar: " + id, ex); }
    }

    private static void requireValidEntry(Entry entry) throws IOException {
        if (entry == null) throw new IOException("Journal fehlt");
        try {
            ShopBuybackReceiptLink.requireValidTransactionId(entry.id());
            ShopBuybackReceiptLink.requireValidTransactionId(entry.playerId());
        } catch (IllegalArgumentException failure) { throw new IOException("Journal-UUID ungueltig", failure); }
        if (entry.itemId() == null || !entry.itemId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || entry.quantity() < 1 || entry.quantity() > 64 || entry.createdAt() <= 0
                || entry.total() < entry.quantity() || entry.total() > entry.quantity() * 1_000_000L
                || entry.total() % entry.quantity() != 0 || !ShopTransactionStages.isKnown(entry.stage()))
            throw new IOException("Journalinhalt ungueltig: " + entry.id());
    }

    private static void store(Entry entry, boolean initial) throws IOException {
        Path target = path(entry.id());
        if (initial && Files.exists(target)) throw new IOException("Journal existiert bereits");
        Path temp = Files.createTempFile(folder, "shop-journal-", ".tmp");
        try {
            byte[] bytes = GSON.toJson(entry).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (initial) {
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ex) { throw new IOException("Atomare Journalanlage nicht unterstuetzt", ex); }
            } else {
                move(temp, target);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private static void archive(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archivierter Beleg existiert bereits: " + target);
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { throw new IOException("Atomare Journalarchivierung nicht unterstuetzt", ex); }
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Atomarer Journalstatuswechsel nicht unterstuetzt", e);
        }
    }
}
