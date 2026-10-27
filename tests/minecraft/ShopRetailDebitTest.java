import de.baluserver.erzmarken.TokenStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Real account file; no shop booking or item delivery is implied. */
public final class ShopRetailDebitTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    private static void denied(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("should be denied"); }
        catch (IOException | IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    private interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("retail-debit-").toAbsolutePath().normalize();
        try {
            Path file = root.resolve("accounts.json");
            TokenStore tokens = new TokenStore(file);
            UUID buyer = UUID.randomUUID();
            check(tokens.add(buyer, 30));
            String id = UUID.randomUUID().toString();
            tokens.debitShopSaleChecked(buyer, 14, id);
            check(tokens.balance(buyer) == 16);
            check(new TokenStore(file).balance(buyer) == 16);
            tokens.debitShopSaleChecked(buyer, 14, id);
            check(tokens.balance(buyer) == 16);
            TokenStore reopened = new TokenStore(file);
            reopened.debitShopSaleChecked(buyer, 14, id);
            check(reopened.balance(buyer) == 16);
            denied(() -> reopened.debitShopSaleChecked(buyer, 13, id));
            denied(() -> reopened.debitShopSaleChecked(UUID.randomUUID(), 14, id));
            denied(() -> reopened.debitShopSaleChecked(buyer, 17, UUID.randomUUID().toString()));
            check(reopened.balance(buyer) == 16);

            Path saved = root.resolve("accounts.saved");
            Files.move(file, saved);
            Files.createDirectory(file);
            denied(() -> reopened.debitShopSaleChecked(buyer, 1, UUID.randomUUID().toString()));
            check(reopened.balance(buyer) == 16);
            denied(() -> reopened.debitShopSaleChecked(buyer, 1, UUID.randomUUID().toString()));
            Files.delete(file);
            Files.move(saved, file);
            check(new TokenStore(file).balance(buyer) == 16);

            Path legacyFile = root.resolve("legacy.json");
            var legacy = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            legacy.remove("shopDebits");
            Files.writeString(legacyFile, legacy.toString());
            TokenStore migrated = new TokenStore(legacyFile);
            check(migrated.balance(buyer) == 16);
            migrated.debitShopSaleChecked(buyer, 3, UUID.randomUUID().toString());
            check(new TokenStore(legacyFile).balance(buyer) == 13);
            System.out.println("ShopRetailDebitTest: " + checks + " checks passed (durable player debit only)");
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
