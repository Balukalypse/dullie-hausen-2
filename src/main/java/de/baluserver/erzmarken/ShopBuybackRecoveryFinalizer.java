package de.baluserver.erzmarken;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit, file-bound operator decision. Only the journal may be completed; never pay or restore items. */
public final class ShopBuybackRecoveryFinalizer {
    /** Live command remains off until Loom/Mixin/server tests succeed. Core is exercised against fixtures. */
    public static final boolean COMMAND_ENABLED = false;
    private static final Gson GSON = new Gson();
    private ShopBuybackRecoveryFinalizer() { }
    @FunctionalInterface public interface InventoryVerifier {
        void verify(byte[] savedFile, UUID player, byte[] expectedSnapshot) throws IOException;
    }
    @FunctionalInterface public interface MemoryCheck { void verify() throws IOException; }
    public record Source(String role, String path, long bytes, String sha256) { }
    public record Ticket(String id, String stage, String confirmation, List<Source> sources) {
        public Ticket { sources = List.copyOf(sources); }
    }
    private record Decision(String action, String id, String stage, String confirmation,
                            List<Source> sources, long requestedAt) { }

    /** Read-only inspection. The returned digest is not authorization and never archives anything. */
    public static Ticket inspect(Path world, String id, boolean online, InventoryVerifier verifier) throws IOException {
        if (online || verifier == null) throw new IOException("Offline-Spieler und unabhaengige Inventarpruefung erforderlich");
        var plan = ShopBuybackIntentStore.read(world, id);
        UUID player = UUID.fromString(plan.expected().playerId());
        Path root = world.toAbsolutePath().normalize();
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("INTENT", root.resolve("data/erzmarken-shop-intents/" + id + ".intent"));
        paths.put("JOURNAL", root.resolve("data/erzmarken-shop-journal/" + id + ".pending.json"));
        paths.put("TOKENS", root.resolve("data/erzmarken.json"));
        paths.put("SHOP", root.resolve("data/erzmarken-shop.json"));
        paths.put("PLAYER", ShopPlayerDataPaths.resolveOffline(root, player));
        Map<String, byte[]> before = new LinkedHashMap<>();
        for (var path : paths.entrySet()) before.put(path.getKey(), read(path.getValue(), limit(path.getKey())));
        var current = ShopTransactionJournal.readPendingForReview(root, id);
        plan = ShopBuybackIntentStore.read(root, id);
        if (!player.toString().equals(plan.expected().playerId())) throw new IOException("Planidentitaet waehrend Pruefung geaendert");
        plan.requireMatches(current, plan.expected(), plan.after());
        if (!Set.of("ITEM_REMOVED_UNSAVED", "COMPLETED_VERIFIED").contains(current.stage()))
            throw new IOException("Recovery-Abschluss nur aus letzter Buchungsphase moeglich");
        if (ShopJournalAudit.inspect(root).suspicious() > 0) throw new IOException("Verdaechtige Journaldateien zuerst pruefen");
        for (String suffix : new String[]{".complete.json", ".cancelled.json"})
            if (Files.exists(root.resolve("data/erzmarken-shop-journal/" + id + suffix), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Archivziel fuer Buchung existiert bereits");
        ShopBuybackSettlement.verifyFinances(root, plan.expected());
        verifier.verify(before.get("PLAYER").clone(), player, plan.after());
        boolean alsoBefore;
        try { verifier.verify(before.get("PLAYER").clone(), player, plan.before()); alsoBefore = true; }
        catch (IOException mismatch) { alsoBefore = false; }
        if (alsoBefore) throw new IOException("Inventarnachweis ist mehrdeutig");
        List<Source> sources = new ArrayList<>();
        for (var path : paths.entrySet()) {
            byte[] stable = read(path.getValue(), limit(path.getKey()));
            if (!Arrays.equals(before.get(path.getKey()), stable)) throw new IOException("Dateien waehrend Pruefung geaendert");
            sources.add(new Source(path.getKey(), root.relativize(path.getValue()).toString().replace('\\', '/'), stable.length, hash(stable)));
        }
        String signed = "DULLIE_RECOVERY_CONFIRM_1\n" + root + "\n" + id + "\n" + GSON.toJson(sources);
        return new Ticket(id, current.stage(), hash(signed.getBytes(StandardCharsets.UTF_8)), sources);
    }

    /** Called only for a deliberate confirmation; rechecks all evidence and live-memory consistency. */
    public static void finishConfirmed(Path world, String id, String confirmation, BooleanSupplier online,
            InventoryVerifier verifier, MemoryCheck memory) throws IOException {
        if (confirmation == null || !confirmation.matches("[0-9a-f]{64}") || online == null || memory == null)
            throw new IOException("Explizite gueltige Bestaetigung und Speicherpruefung erforderlich");
        ShopTransactionJournal.requireWorld(world);
        memory.verify();
        Ticket ticket = inspect(world, id, online.getAsBoolean(), verifier);
        if (!ticket.confirmation().equals(confirmation)) throw new IOException("Bestaetigung veraltet oder gehoert zu anderen Dateien");
        recordDecision(world, ticket);
        memory.verify();
        Ticket fresh = inspect(world, id, online.getAsBoolean(), verifier);
        if (!fresh.equals(ticket) || online.getAsBoolean()) throw new IOException("Zustand vor Abschluss veraendert");
        ShopTransactionJournal.finishReviewed(ShopTransactionJournal.readPendingForReview(world, id));
    }

    private static void recordDecision(Path world, Ticket ticket) throws IOException {
        Path file = world.toAbsolutePath().normalize().resolve("data/erzmarken-shop-recovery-decisions")
            .resolve(ticket.id() + "-" + ticket.confirmation() + ".json");
        safePath(file); Files.createDirectories(file.getParent());
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                var raw = StrictJsonFiles.readObject(file, 64 * 1024);
                raw.get("requestedAt").getAsBigDecimal().longValueExact();
                for (var source : raw.getAsJsonArray("sources"))
                    source.getAsJsonObject().get("bytes").getAsBigDecimal().longValueExact();
                Decision old = GSON.fromJson(raw, Decision.class);
                if (!"CONSOLE_COMPLETION_REQUEST".equals(old.action()) || !ticket.id().equals(old.id())
                        || !ticket.stage().equals(old.stage()) || !ticket.confirmation().equals(old.confirmation())
                        || !ticket.sources().equals(old.sources()) || old.requestedAt() <= 0)
                    throw new IOException("Bestehender Recovery-Entscheidungsbeleg widerspruechlich");
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
                return;
            } catch (RuntimeException corrupt) { throw new IOException("Recovery-Entscheidungsbeleg unlesbar", corrupt); }
        }
        byte[] data = GSON.toJson(new Decision("CONSOLE_COMPLETION_REQUEST", ticket.id(), ticket.stage(),
            ticket.confirmation(), ticket.sources(), System.currentTimeMillis())).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static long limit(String role) { return role.equals("JOURNAL") ? 1024 * 1024 : role.equals("INTENT") ? 4 * 1024 * 1024 : 16 * 1024 * 1024; }
    private static byte[] read(Path file, long limit) throws IOException {
        safePath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > limit)
            throw new IOException("Recovery-Quelldatei fehlt oder ist zu gross");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > limit) throw new IOException("Quelldatei waehrend Lesen gewachsen");
        return bytes;
    }
    private static void safePath(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize(), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Verlinkter Recovery-Pfad");
            if (!cursor.equals(absolute) && Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Recovery-Pfad ist kein Verzeichnis");
        }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
