import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Actual journal and token files: partial execution, restart and stale writers. */
public final class ShopJournalPersistenceTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    interface IO { void run() throws IOException; }
    static void denied(IO operation) throws IOException {
        try { operation.run(); throw new AssertionError("must refuse"); } catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("journal-persistence-");
        try {
            ShopTransactionJournal.initialize(root);
            UUID player = UUID.randomUUID();
            var prepared = ShopTransactionJournal.begin(player, "minecraft:stone", 2, 14);
            var debited = ShopTransactionJournal.advance(prepared, "SHOP_DEBITED");
            Path tokenFile = root.resolve("data/erzmarken.json");
            TokenStore tokens = new TokenStore(tokenFile);
            // Simulate interruption after credit publication and before journal advancement.
            tokens.creditShopPurchaseChecked(player, 14, prepared.id());
            ShopTransactionJournal.initialize(root);
            denied(ShopTransactionJournal::ensureClear);
            denied(() -> ShopTransactionJournal.begin(player, "minecraft:stone", 2, 14));
            TokenStore restarted = new TokenStore(tokenFile);
            restarted.creditShopPurchaseChecked(player, 14, prepared.id());
            check(restarted.balance(player) == 14);
            var receiptReport = ShopTokenReceiptAudit.inspect(root, ShopJournalAudit.inspect(root), 4);
            check(receiptReport.matched() == 1 && receiptReport.reviewRequired());
            Path pending = root.resolve("data/erzmarken-shop-journal/" + prepared.id() + ".pending.json");
            byte[] before = Files.readAllBytes(pending);
            denied(() -> ShopTransactionJournal.advance(prepared, "SHOP_DEBITED"));
            var forged = new ShopTransactionJournal.Entry(debited.id(), debited.playerId(), debited.itemId(),
                debited.quantity(), 999, debited.stage(), debited.createdAt());
            denied(() -> ShopTransactionJournal.advance(forged, "PLAYER_CREDITED"));
            check(Arrays.equals(before, Files.readAllBytes(pending)));
            var credited = ShopTransactionJournal.advance(debited, "PLAYER_CREDITED");
            var removed = ShopTransactionJournal.advance(credited, "ITEM_REMOVED_UNSAVED");
            ShopTransactionJournal.initialize(root);
            denied(ShopTransactionJournal::ensureClear);
            check(Files.readString(pending).contains("ITEM_REMOVED_UNSAVED"));
            check(new TokenStore(tokenFile).balance(player) == 14);
            // Never finish merely because files exist: actual playerdata proof is absent.
            check(!Files.exists(pending.resolveSibling(removed.id() + ".complete.json")));
            check(ShopTokenReceiptAudit.inspect(root, ShopJournalAudit.inspect(root), 4).reviewRequired());
            System.out.println("Journal persistence: " + checks + " checks passed (restart, retry, stale/forged writers)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
