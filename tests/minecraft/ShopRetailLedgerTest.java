import de.baluserver.erzmarken.ShopState;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Real durable shop file; no player debit or Minecraft item delivery is implied. */
public final class ShopRetailLedgerTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    private static void denied(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("should be denied"); }
        catch (IOException | IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("retail-ledger-").toAbsolutePath().normalize();
        try {
            Path file = root.resolve("shop.json");
            ShopState shop = new ShopState(file);
            shop.setOwner(UUID.randomUUID());
            shop.setRegion("minecraft:overworld", new int[]{0,0,0}, new int[]{10,100,10});
            shop.setWholesaleUnitPrice("minecraft:stone", 3);
            shop.buyWholesale("minecraft:stone", 10);
            shop.setRetailUnitPrice("minecraft:stone", 7);
            UUID buyer = UUID.randomUUID();
            var quote = shop.retailQuote("minecraft:stone", 2);
            check(quote.available() && quote.totalPrice() == 14 && quote.availableStock() == 10);
            String id = UUID.randomUUID().toString();
            var receipt = shop.commitRetail(buyer, quote, id);
            check(receipt.id().equals(id) && receipt.playerUuid().equals(buyer.toString()));
            check(receipt.paid() == 14 && receipt.walletAfter() == 2484 && receipt.stockAfter() == 8);
            ShopState fresh = new ShopState(file);
            check(fresh.shopWallet() == 2484 && fresh.wholesaleStock("minecraft:stone") == 8);
            check(fresh.retailHistory().size() == 1 && fresh.retailHistory().get(0).equals(receipt));
            denied(() -> shop.commitRetail(buyer, quote, id));
            denied(() -> shop.commitRetail(buyer, quote, UUID.randomUUID().toString()));
            check(shop.retailHistory().size() == 1 && shop.shopWallet() == 2484);

            Path original = root.resolve("shop.saved");
            Files.move(file, original);
            Files.createDirectory(file);
            var current = shop.retailQuote("minecraft:stone", 1);
            denied(() -> shop.commitRetail(buyer, current, UUID.randomUUID().toString()));
            check(shop.shopWallet() == 2484 && shop.wholesaleStock("minecraft:stone") == 8);
            check(shop.retailHistory().size() == 1);
            Files.delete(file);
            Files.move(original, file);

            var next = shop.retailQuote("minecraft:stone", 1);
            Files.move(file, original);
            denied(() -> shop.commitRetail(buyer, next, UUID.randomUUID().toString()));
            check(shop.shopWallet() == 2484 && shop.wholesaleStock("minecraft:stone") == 8);
            check(shop.retailHistory().size() == 1);
            check(new ShopState(original).retailHistory().size() == 1);

            // An existing .16-or-earlier world has no retailHistory field yet.
            Path legacyFile = root.resolve("legacy.json");
            var legacy = com.google.gson.JsonParser.parseString(Files.readString(original)).getAsJsonObject();
            legacy.remove("retailHistory");
            Files.writeString(legacyFile, legacy.toString());
            ShopState migrated = new ShopState(legacyFile);
            check(migrated.retailHistory().isEmpty());
            migrated.requirePersistedState();
            check(migrated.wholesaleStock("minecraft:stone") == 8);
            var migratedQuote = migrated.retailQuote("minecraft:stone", 1);
            migrated.commitRetail(buyer, migratedQuote, UUID.randomUUID().toString());
            check(new ShopState(legacyFile).retailHistory().size() == 1);
            System.out.println("ShopRetailLedgerTest: " + checks + " checks passed (durable shop ledger only)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Unsafe cleanup");
                    Files.delete(p);
                }
            }
        }
    }
}
