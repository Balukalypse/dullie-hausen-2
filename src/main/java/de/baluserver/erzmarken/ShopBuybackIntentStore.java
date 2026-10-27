package de.baluserver.erzmarken;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Write-once transaction plan, forced to disk BEFORE any money/item mutation. */
public final class ShopBuybackIntentStore {
    private static final Gson GSON = new Gson();
    private static final String MAGIC = "DULLIE_BUYBACK_INTENT_1\n";
    private static final int MAX_SNAPSHOT = 1024 * 1024, MAX_FILE = 4 * 1024 * 1024;
    private ShopBuybackIntentStore() { }
    public record Intent(ShopTransactionJournal.Entry prepared, ShopBuybackSettlement.Expected expected,
                         byte[] before, byte[] after) {
        public Intent {
            if (prepared == null || expected == null || !"PREPARED".equals(prepared.stage())
                    || prepared.createdAt() <= 0 || before == null || after == null
                    || before.length == 0 || after.length == 0 || before.length > MAX_SNAPSHOT
                    || after.length > MAX_SNAPSHOT || Arrays.equals(before, after)
                    || !same(prepared, expected)) throw new IllegalArgumentException("Ungueltiger Buchungsplan");
            before = before.clone(); after = after.clone();
        }
        @Override public byte[] before() { return before.clone(); }
        @Override public byte[] after() { return after.clone(); }
        public void requireMatches(ShopTransactionJournal.Entry current,
                ShopBuybackSettlement.Expected values, byte[] inventoryAfter) throws IOException {
            if (!expected.equals(values) || !Arrays.equals(after, inventoryAfter)
                    || !ShopJournalTransitionGuard.sameImmutableData(prepared.id(), prepared.playerId(), prepared.itemId(),
                        prepared.quantity(), prepared.total(), prepared.createdAt(), current.id(), current.playerId(),
                        current.itemId(), current.quantity(), current.total(), current.createdAt()))
                throw new IOException("Buchung widerspricht dem urspruenglichen Plan");
        }
    }
    private record Wire(ShopTransactionJournal.Entry prepared, ShopBuybackSettlement.Expected expected,
                        String before, String after) { }
    private static boolean same(ShopTransactionJournal.Entry entry, ShopBuybackSettlement.Expected expected) {
        return expected.id().equals(entry.id()) && expected.playerId().equals(entry.playerId())
            && expected.itemId().equals(entry.itemId()) && expected.quantity() == entry.quantity()
            && expected.total() == entry.total();
    }
    public static synchronized void prepare(Path world, ShopTransactionJournal.Entry entry,
            ShopBuybackSettlement.Expected expected, byte[] before, byte[] after) throws IOException {
        ShopTransactionJournal.requireWorld(world); ShopTransactionJournal.requirePending(entry);
        Intent intent = new Intent(entry, expected, before, after);
        Path file = path(world, entry.id()); checkPath(file);
        Files.createDirectories(file.getParent());
        byte[] payload = GSON.toJson(new Wire(intent.prepared(), intent.expected(),
            Base64.getEncoder().encodeToString(intent.before()), Base64.getEncoder().encodeToString(intent.after())))
            .getBytes(StandardCharsets.UTF_8);
        byte[] output = (MAGIC + hash(payload) + "\n" + new String(payload, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        if (output.length > MAX_FILE) throw new IOException("Buchungsplan ueberschreitet Leselimit");
        // CREATE_NEW never replaces evidence. A failed partial write remains untrusted evidence;
        // no financial mutation is allowed until this operation has fully succeeded.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(output);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    public static Intent read(Path world, String id) throws IOException {
        Path file = path(world, id); checkPath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE)
            throw new IOException("Buchungsplan fehlt, ist unzulaessig oder zu gross");
        byte[] all = Files.readAllBytes(file);
        byte[] prefix = MAGIC.getBytes(StandardCharsets.UTF_8);
        if (all.length < prefix.length + 66 || !Arrays.equals(prefix, Arrays.copyOf(all, prefix.length))
                || all[prefix.length + 64] != '\n') throw new IOException("Buchungsplanhuelle ungueltig");
        byte[] payload = Arrays.copyOfRange(all, prefix.length + 65, all.length);
        String checksum = new String(all, prefix.length, 64, StandardCharsets.US_ASCII);
        if (!hash(payload).equals(checksum)) throw new IOException("Buchungsplan-Pruefsumme passt nicht");
        try {
            var object = StrictJsonFiles.readObject(payload, MAX_FILE);
            // Reject rounded metadata before Gson converts integer fields.
            object.getAsJsonObject("prepared").get("quantity").getAsBigDecimal().intValueExact();
            object.getAsJsonObject("expected").get("quantity").getAsBigDecimal().intValueExact();
            for (String field : new String[]{"total", "createdAt"})
                object.getAsJsonObject("prepared").get(field).getAsBigDecimal().longValueExact();
            for (String field : new String[]{"unitPrice", "total", "playerBalanceAfter", "walletAfter", "stockAfter"})
                object.getAsJsonObject("expected").get(field).getAsBigDecimal().longValueExact();
            Wire wire = GSON.fromJson(object, Wire.class);
            Intent intent = new Intent(wire.prepared(), wire.expected(), Base64.getDecoder().decode(wire.before()),
                Base64.getDecoder().decode(wire.after()));
            if (!id.equals(intent.prepared().id())) throw new IOException("Falsche Buchungsplan-ID");
            return intent;
        } catch (RuntimeException failure) { throw new IOException("Buchungsplan unlesbar", failure); }
    }
    private static Path path(Path world, String id) throws IOException {
        try { ShopBuybackReceiptLink.requireValidTransactionId(id); }
        catch (IllegalArgumentException invalid) { throw new IOException("Ungueltige Plan-ID", invalid); }
        return world.toAbsolutePath().normalize().resolve("data/erzmarken-shop-intents").resolve(id + ".intent");
    }
    private static void checkPath(Path file) throws IOException {
        Path cursor = file.getRoot();
        for (Path part : file) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Verlinkter Buchungsplanpfad");
            if (!cursor.equals(file) && Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Planpfad ist kein Verzeichnis");
        }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
