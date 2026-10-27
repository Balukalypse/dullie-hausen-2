package de.baluserver.erzmarken;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Separate retail journal. A pending entry blocks further trade; no automatic replay. */
public final class ShopRetailJournal {
    private static final Gson GSON = new Gson();
    private static final int MAX_ENTRY = 64 * 1024;
    private ShopRetailJournal() { }
    public record Entry(String id, String playerId, String itemId, int quantity, long total,
                        String stage, long createdAt) { }
    private static final List<String> STAGES = List.of("PREPARED", "PLAYER_DEBITED",
        "SHOP_CREDITED", "ITEM_DELIVERED_UNSAVED");

    public static synchronized Entry begin(Path world, ShopRetailPlanStore.Plan plan) throws IOException {
        if (plan == null) throw new IllegalArgumentException("Verkaufsplan fehlt");
        requireClear(world);
        var saved = ShopRetailPlanStore.read(world, plan.id());
        if (!samePlan(saved, plan)) throw new IOException("Verkaufsplan weicht von der Datei ab");
        Entry entry = new Entry(plan.id(), plan.player().toString(), plan.itemId(), plan.quantity(),
            plan.total(), "PREPARED", System.currentTimeMillis());
        validate(entry);
        Path file = path(world, entry.id());
        if (Files.exists(file.resolveSibling(entry.id() + ".complete.json"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Verkaufs-ID bereits abgeschlossen");
        Files.createDirectories(file.getParent());
        byte[] bytes = GSON.toJson(entry).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) channel.write(source);
            channel.force(true);
        }
        return entry;
    }

    public static synchronized Entry advance(Path world, Entry prior, String next) throws IOException {
        if (prior == null || next == null) throw new IllegalArgumentException("Journalstufe fehlt");
        Entry disk = read(world, prior.id());
        if (!disk.equals(prior)) throw new IOException("Verkaufsjournal wurde seit dem Lesen geaendert");
        int index = STAGES.indexOf(prior.stage());
        if (index < 0 || index + 1 >= STAGES.size() || !STAGES.get(index + 1).equals(next))
            throw new IOException("Ungueltiger Verkaufsjournal-Uebergang");
        Entry updated = new Entry(prior.id(), prior.playerId(), prior.itemId(), prior.quantity(),
            prior.total(), next, prior.createdAt());
        DurableFiles.replace(path(world, prior.id()), GSON.toJson(updated).getBytes(StandardCharsets.UTF_8));
        return updated;
    }

    public static Entry read(Path world, String id) throws IOException {
        return readFile(world, path(world, id), id);
    }

    public static Entry readCompleted(Path world, String id) throws IOException {
        ShopBuybackReceiptLink.requireValidTransactionId(id);
        Entry entry = readFile(world, folder(world).resolve(id + ".complete.json"), id);
        if (!"COMPLETED_VERIFIED".equals(entry.stage())) throw new IOException("Verkaufsabschluss fehlt");
        return entry;
    }

    /** Read-only administration view; never closes or replays a purchase. */
    public static List<Entry> inspect(Path world) throws IOException {
        Path folder = folder(world);
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path file : entries) {
                if (result.size() >= 5000) throw new IOException("Verkaufsjournal ueberschreitet Anzeigelimit");
                String name = file.getFileName().toString();
                boolean complete = name.endsWith(".complete.json");
                String suffix = complete ? ".complete.json" : ".pending.json";
                if (!name.endsWith(suffix)) throw new IOException("Unbekannter Verkaufsjournal-Eintrag");
                String id = name.substring(0, name.length() - suffix.length());
                result.add(complete ? readCompleted(world, id) : read(world, id));
            }
        }
        result.sort(Comparator.comparingLong(Entry::createdAt).reversed());
        return List.copyOf(result);
    }

    private static Entry readFile(Path world, Path file, String id) throws IOException {
        var raw = StrictJsonFiles.readObject(file, MAX_ENTRY);
        try {
            raw.get("quantity").getAsBigDecimal().intValueExact();
            raw.get("total").getAsBigDecimal().longValueExact();
            raw.get("createdAt").getAsBigDecimal().longValueExact();
            Entry entry = GSON.fromJson(raw, Entry.class);
            validate(entry);
            if (!id.equals(entry.id())) throw new IOException("Verkaufsjournal-ID widerspricht Dateiname");
            var plan = ShopRetailPlanStore.read(world, id);
            if (!entry.playerId().equals(plan.player().toString()) || !entry.itemId().equals(plan.itemId())
                || entry.quantity() != plan.quantity() || entry.total() != plan.total())
                throw new IOException("Verkaufsjournal widerspricht Plan");
            return entry;
        } catch (RuntimeException failure) { throw new IOException("Verkaufsjournal unlesbar", failure); }
    }

    public static void requireClear(Path world) throws IOException {
        if (ShopJournalAudit.inspectForAdmission(world).blocksTrading())
            throw new IOException("Ankaufsjournal offen oder auffaellig");
        Path folder = folder(world);
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".complete.json"))
                    throw new IOException("Verkaufsjournal enthaelt offene oder unbekannte Datei: " + name);
                readCompleted(world, name.substring(0, name.length() - ".complete.json".length()));
            }
        }
    }

    /** Any unreviewed file blocks admission; malformed files cannot be attributed safely. */
    public static boolean mayJoin(Path world, UUID player) throws IOException {
        Path folder = folder(world);
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return true;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".complete.json")) {
                    try { readCompleted(world, name.substring(0, name.length() - ".complete.json".length())); }
                    catch (IOException | RuntimeException invalid) { return false; }
                    continue;
                }
                if (!name.endsWith(".pending.json")) return false;
                String id = name.substring(0, name.length() - ".pending.json".length());
                try { if (read(world, id).playerId().equals(player.toString())) return false; }
                catch (IOException | RuntimeException invalid) { return false; }
            }
        }
        return true;
    }

    public static void requireDebit(Path world, UUID player, String id, long amount) throws IOException {
        Path folder = folder(world);
        int count = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path file : entries) {
                String name = file.getFileName().toString();
                if (name.endsWith(".complete.json")) {
                    readCompleted(world, name.substring(0, name.length() - ".complete.json".length()));
                    continue;
                }
                if (!file.getFileName().toString().equals(id + ".pending.json"))
                    throw new IOException("Weiterer oder unbekannter Verkaufsjournal-Eintrag");
                count++;
            }
        }
        if (count != 1) throw new IOException("Verkaufsjournal nicht eindeutig");
        Entry entry = read(world, id);
        if (player == null || !player.toString().equals(entry.playerId()) || amount != entry.total()
            || !"PREPARED".equals(entry.stage()))
            throw new IOException("Spielerabbuchung passt nicht zum offenen Verkaufsjournal");
    }

    /** Only settlement after saved inventory and independent finance readback may call this. */
    static synchronized void finishVerified(Path world, Entry prior) throws IOException {
        if (prior == null || !"ITEM_DELIVERED_UNSAVED".equals(prior.stage())
            || !read(world, prior.id()).equals(prior)) throw new IOException("Verkaufsabschluss aus falschem Zustand");
        Path pending = path(world, prior.id());
        Path completed = folder(world).resolve(prior.id() + ".complete.json");
        if (Files.exists(completed, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Verkaufsbeleg bereits abgeschlossen");
        Entry done = new Entry(prior.id(), prior.playerId(), prior.itemId(), prior.quantity(),
            prior.total(), "COMPLETED_VERIFIED", prior.createdAt());
        DurableFiles.replace(pending, GSON.toJson(done).getBytes(StandardCharsets.UTF_8));
        // Refuse a non-atomic fallback. A failed move retains a pending review case.
        Files.move(pending, completed, StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean samePlan(ShopRetailPlanStore.Plan a, ShopRetailPlanStore.Plan b) {
        return a.id().equals(b.id()) && a.player().equals(b.player()) && a.itemId().equals(b.itemId())
            && a.quantity() == b.quantity() && a.unitPrice() == b.unitPrice()
            && a.playerBalanceBefore() == b.playerBalanceBefore() && a.walletBefore() == b.walletBefore()
            && a.stockBefore() == b.stockBefore()
            && Arrays.equals(a.inventoryBefore(), b.inventoryBefore())
            && Arrays.equals(a.inventoryAfter(), b.inventoryAfter());
    }
    private static void validate(Entry entry) throws IOException {
        if (entry == null) throw new IOException("Verkaufsjournal fehlt");
        try { ShopBuybackReceiptLink.requireValidTransactionId(entry.id());
            ShopBuybackReceiptLink.requireValidTransactionId(entry.playerId()); }
        catch (RuntimeException invalid) { throw new IOException("Verkaufsjournal-ID ungueltig", invalid); }
        if (entry.itemId() == null || !entry.itemId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
            || entry.quantity() < 1 || entry.quantity() > 64 || entry.total() < 1
            || entry.total() > TokenStore.MAX_TOKENS
            || (!STAGES.contains(entry.stage()) && !"COMPLETED_VERIFIED".equals(entry.stage()))
            || entry.createdAt() <= 0) throw new IOException("Verkaufsjournal ungueltig");
    }
    private static Path path(Path world, String id) throws IOException {
        ShopBuybackReceiptLink.requireValidTransactionId(id);
        return folder(world).resolve(id + ".pending.json");
    }
    private static Path folder(Path world) throws IOException {
        if (world == null) throw new IOException("Weltpfad fehlt");
        Path folder = world.toAbsolutePath().normalize().resolve("data/erzmarken-retail-journal");
        Path cursor = folder.getRoot();
        for (Path part : folder) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Verlinkter Verkaufsjournalpfad");
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Verkaufsjournalpfad ist kein Verzeichnis");
        }
        return folder;
    }
}
