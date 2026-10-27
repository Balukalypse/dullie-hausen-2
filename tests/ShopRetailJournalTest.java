import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ShopRetailJournalTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    private static void denied(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("should fail"); }
        catch (IOException | IllegalArgumentException expected) { checks++; }
    }
    private interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("retail-journal-").toAbsolutePath().normalize();
        try {
            UUID buyer = UUID.randomUUID(), other = UUID.randomUUID();
            String id = UUID.randomUUID().toString();
            TokenStore tokens = TokenStore.forWorld(root);
            check(tokens.add(buyer, 30));
            var plan = new ShopRetailPlanStore.Plan(id, buyer, "minecraft:stone", 2, 7,
                30, 2470, 10, new byte[]{1}, new byte[]{2});
            ShopRetailPlanStore.prepare(root, plan);
            check(ShopRetailJournal.mayJoin(root, buyer));
            var entry = ShopRetailJournal.begin(root, plan);
            check("PREPARED".equals(entry.stage()));
            check(ShopRetailJournal.read(root, id).equals(entry));
            check(!ShopPendingTradeGate.mayJoin(root, buyer));
            check(ShopPendingTradeGate.mayJoin(root, other));
            check(!tokens.take(buyer, 1));
            denied(() -> ShopRetailJournal.begin(root, plan));
            denied(() -> ShopRetailJournal.advance(root, entry, "SHOP_CREDITED"));
            denied(() -> tokens.debitShopSaleChecked(buyer, 13, id));
            tokens.debitShopSaleChecked(buyer, 14, id);
            check(tokens.balance(buyer) == 16);
            check(new TokenStore(root.resolve("data/erzmarken.json")).balance(buyer) == 16);
            var debited = ShopRetailJournal.advance(root, entry, "PLAYER_DEBITED");
            check("PLAYER_DEBITED".equals(ShopRetailJournal.read(root, id).stage()));
            denied(() -> ShopRetailJournal.advance(root, entry, "PLAYER_DEBITED"));
            denied(() -> ShopRetailJournal.requireDebit(root, buyer, id, 14));
            denied(() -> ShopRetailJournal.requireClear(root));
            ShopTransactionJournal.initialize(root);
            denied(() -> ShopTransactionJournal.begin(other, "minecraft:stone", 1, 1));
            var credited = ShopRetailJournal.advance(root, debited, "SHOP_CREDITED");
            var delivered = ShopRetailJournal.advance(root, credited, "ITEM_DELIVERED_UNSAVED");
            check("ITEM_DELIVERED_UNSAVED".equals(delivered.stage()));
            denied(() -> ShopRetailJournal.advance(root, delivered, "COMPLETED_VERIFIED"));
            Path file = root.resolve("data/erzmarken-retail-journal/" + id + ".pending.json");
            Files.writeString(file, "{bad");
            check(!ShopRetailJournal.mayJoin(root, buyer));
            check(!ShopRetailJournal.mayJoin(root, other));
            System.out.println("ShopRetailJournalTest: " + checks + " checks passed");
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
