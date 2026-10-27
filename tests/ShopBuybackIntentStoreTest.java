import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class ShopBuybackIntentStoreTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    interface IO { void run() throws IOException; }
    static void denied(IO op) throws IOException {
        try { op.run(); throw new AssertionError("must refuse"); }
        catch (IOException | IllegalArgumentException expected) { checks++; }
    }
    static byte[] envelope(String payload) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        return ("DULLIE_BUYBACK_INTENT_1\n" + hash + "\n" + payload).getBytes(StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        Path world = Files.createTempDirectory("immutable-buyback-plan-");
        try {
            ShopTransactionJournal.initialize(world);
            UUID player = UUID.randomUUID();
            var entry = ShopTransactionJournal.begin(player, "minecraft:stone", 2, 14);
            var expected = new ShopBuybackSettlement.Expected(entry.id(), player.toString(), entry.itemId(), 2, 7, 14, 114, 2486, 2);
            byte[] before = {1, 2}, after = {1, 1};
            ShopBuybackIntentStore.prepare(world, entry, expected, before, after);
            Path file = world.resolve("data/erzmarken-shop-intents/" + entry.id() + ".intent");
            byte[] original = Files.readAllBytes(file);
            before[0] = 9; after[0] = 9;
            var plan = ShopBuybackIntentStore.read(world, entry.id());
            check(Arrays.equals(plan.before(), new byte[]{1, 2}));
            check(Arrays.equals(plan.after(), new byte[]{1, 1}));
            byte[] changed = plan.after(); changed[0] = 9;
            check(Arrays.equals(plan.after(), new byte[]{1, 1}));
            denied(() -> ShopBuybackIntentStore.prepare(world, entry, expected, before, after));
            check(Arrays.equals(original, Files.readAllBytes(file)));
            denied(() -> ShopBuybackIntentStore.read(world, "../../outside"));
            denied(() -> ShopBuybackIntentStore.read(world, UUID.randomUUID().toString()));
            denied(() -> plan.requireMatches(entry, expected, new byte[]{5}));
            var other = new ShopBuybackSettlement.Expected(entry.id(), player.toString(), entry.itemId(), 2, 7, 14, 115, 2486, 2);
            denied(() -> plan.requireMatches(entry, other, plan.after()));
            String payload = new String(original, StandardCharsets.UTF_8).split("\n", 3)[2];
            for (byte[] invalid : new byte[][]{
                    Arrays.copyOf(original, 20), "not an intent".getBytes(StandardCharsets.UTF_8),
                    envelope(payload.replace("\"quantity\":2", "\"quantity\":4294967298")),
                    envelope(payload.replace("\"stockAfter\":2", "\"stockAfter\":2.5")),
                    envelope(payload.replace("\"stage\":\"PREPARED\"", "\"stage\":\"SHOP_DEBITED\"")),
                    envelope(payload.replace("\"total\":14", "\"total\":14,\"total\":14"))}) {
                Files.write(file, invalid);
                denied(() -> ShopBuybackIntentStore.read(world, entry.id()));
                check(Arrays.equals(invalid, Files.readAllBytes(file)));
            }
            byte[] brokenHash = original.clone(); brokenHash[brokenHash.length - 2] ^= 1;
            Files.write(file, brokenHash); denied(() -> ShopBuybackIntentStore.read(world, entry.id()));
            Files.write(file, original);
            // Reading a copied plan must not switch the active journal context.
            Path copyWorld = world.resolve("offline-copy");
            Path copyFile = copyWorld.resolve("data/erzmarken-shop-intents/" + entry.id() + ".intent");
            Files.createDirectories(copyFile.getParent()); Files.copy(file, copyFile);
            check(ShopBuybackIntentStore.read(copyWorld, entry.id()).expected().equals(expected));
            var debited = ShopTransactionJournal.advance(entry, "SHOP_DEBITED");
            plan.requireMatches(debited, expected, plan.after()); checks++;
            denied(() -> ShopBuybackIntentStore.prepare(world, debited, expected, plan.before(), plan.after()));
            check(Arrays.equals(original, Files.readAllBytes(file)));
            System.out.println("Immutable buyback intent: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(world)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
