package de.baluserver.erzmarken;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Write-once retail plan. No token, shop or inventory mutation happens here. */
public final class ShopRetailPlanStore {
    private static final Gson GSON = new Gson();
    private static final String MAGIC = "DULLIE_RETAIL_PLAN_1\n";
    private static final int MAX_SNAPSHOT = 1024 * 1024, MAX_FILE = 4 * 1024 * 1024;
    private ShopRetailPlanStore() { }

    public record Plan(String id, UUID player, String itemId, int quantity, long unitPrice,
            long playerBalanceBefore, long walletBefore, long stockBefore,
            byte[] inventoryBefore, byte[] inventoryAfter) {
        public Plan {
            ShopBuybackReceiptLink.requireValidTransactionId(id);
            if (player == null || itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || quantity < 1 || quantity > 64 || unitPrice < 1 || unitPrice > 1_000_000L
                || playerBalanceBefore < unitPrice * quantity || playerBalanceBefore > TokenStore.MAX_TOKENS
                || walletBefore < 0 || walletBefore > TokenStore.MAX_TOKENS - unitPrice * quantity
                || stockBefore < quantity || stockBefore > 1_000_000L
                || inventoryBefore == null || inventoryAfter == null
                || inventoryBefore.length == 0 || inventoryAfter.length == 0
                || inventoryBefore.length > MAX_SNAPSHOT || inventoryAfter.length > MAX_SNAPSHOT
                || Arrays.equals(inventoryBefore, inventoryAfter))
                throw new IllegalArgumentException("Ungueltiger Verkaufsplan");
            inventoryBefore = inventoryBefore.clone(); inventoryAfter = inventoryAfter.clone();
        }
        @Override public byte[] inventoryBefore() { return inventoryBefore.clone(); }
        @Override public byte[] inventoryAfter() { return inventoryAfter.clone(); }
        public long total() { return unitPrice * quantity; }
        public long playerBalanceAfter() { return playerBalanceBefore - total(); }
        public long walletAfter() { return walletBefore + total(); }
        public long stockAfter() { return stockBefore - quantity; }
    }
    private record Wire(String id, String player, String itemId, int quantity, long unitPrice,
            long playerBalanceBefore, long walletBefore, long stockBefore,
            String inventoryBefore, String inventoryAfter) { }

    public static void prepare(Path world, Plan plan) throws IOException {
        if (plan == null) throw new IllegalArgumentException("Verkaufsplan fehlt");
        Path file = path(world, plan.id());
        byte[] payload = GSON.toJson(new Wire(plan.id(), plan.player().toString(), plan.itemId(),
            plan.quantity(), plan.unitPrice(), plan.playerBalanceBefore(), plan.walletBefore(),
            plan.stockBefore(), Base64.getEncoder().encodeToString(plan.inventoryBefore()),
            Base64.getEncoder().encodeToString(plan.inventoryAfter()))).getBytes(StandardCharsets.UTF_8);
        byte[] output = (MAGIC + hash(payload) + "\n" + new String(payload, StandardCharsets.UTF_8))
            .getBytes(StandardCharsets.UTF_8);
        if (output.length > MAX_FILE) throw new IOException("Verkaufsplan ist zu gross");
        Files.createDirectories(file.getParent());
        // CREATE_NEW preserves even a partial failed write for manual inspection.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap(output);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    public static Plan read(Path world, String id) throws IOException {
        Path file = path(world, id);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE)
            throw new IOException("Verkaufsplan fehlt oder ist zu gross");
        byte[] all = Files.readAllBytes(file), prefix = MAGIC.getBytes(StandardCharsets.UTF_8);
        if (all.length < prefix.length + 66 || !Arrays.equals(prefix, Arrays.copyOf(all, prefix.length))
            || all[prefix.length + 64] != '\n') throw new IOException("Verkaufsplanhuelle ungueltig");
        byte[] payload = Arrays.copyOfRange(all, prefix.length + 65, all.length);
        if (!hash(payload).equals(new String(all, prefix.length, 64, StandardCharsets.US_ASCII)))
            throw new IOException("Verkaufsplan-Pruefsumme passt nicht");
        try {
            var raw = StrictJsonFiles.readObject(payload, MAX_FILE);
            raw.get("quantity").getAsBigDecimal().intValueExact();
            for (String name : new String[]{"unitPrice", "playerBalanceBefore", "walletBefore", "stockBefore"})
                raw.get(name).getAsBigDecimal().longValueExact();
            Wire wire = GSON.fromJson(raw, Wire.class);
            Plan plan = new Plan(wire.id(), UUID.fromString(wire.player()), wire.itemId(),
                wire.quantity(), wire.unitPrice(), wire.playerBalanceBefore(), wire.walletBefore(),
                wire.stockBefore(), Base64.getDecoder().decode(wire.inventoryBefore()),
                Base64.getDecoder().decode(wire.inventoryAfter()));
            if (!id.equals(plan.id()) || !wire.player().equals(plan.player().toString()))
                throw new IOException("Verkaufsplan-ID oder Spieler ungueltig");
            return plan;
        } catch (RuntimeException failure) { throw new IOException("Verkaufsplan unlesbar", failure); }
    }
    private static Path path(Path world, String id) throws IOException {
        ShopBuybackReceiptLink.requireValidTransactionId(id);
        if (world == null) throw new IOException("Weltpfad fehlt");
        Path file = world.toAbsolutePath().normalize().resolve("data/erzmarken-retail-plans/").resolve(id + ".plan");
        Path cursor = file.getRoot();
        for (Path part : file) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Verlinkter Verkaufsplanpfad");
            if (!cursor.equals(file) && Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Verkaufsplanpfad ist kein Verzeichnis");
        }
        return file;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
