import de.baluserver.erzmarken.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Real ShopState, TokenStore and Journal. Only the vanilla inventory saver is a test double. */
public class ShopBuybackPersistenceIntegrationTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    interface IO { void run() throws IOException; }
    static void denied(IO op) throws IOException {
        try { op.run(); throw new AssertionError("must refuse"); } catch (IOException expected) { checks++; }
    }
    static class Inventory implements ShopInventorySwitchController.InventoryAdapter {
        byte[] live = "AFTER_REMOVAL".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final Path file;
        boolean failSave, failAfterWrite, mutateAfterSave;
        int saves;
        Inventory(Path file) throws IOException { this.file = file; Files.writeString(file, "BEFORE_REMOVAL"); }
        public byte[] snapshot() { return live.clone(); }
        public void apply(byte[] ignored) { throw new AssertionError("settlement must not apply/rollback items"); }
        public void clear() { throw new AssertionError("settlement must not clear items"); }
        public boolean isEmpty() { return false; }
        public void requirePersistenceSupport() { }
        public void persistAndVerify(byte[] expected) throws IOException {
            if (failSave) throw new IOException("simulated playerdata failure");
            Files.write(file, live); saves++;
            if (failAfterWrite) throw new IOException("simulated ambiguous save outcome");
            if (!Arrays.equals(expected, Files.readAllBytes(file))) throw new IOException("saved inventory mismatch");
            if (mutateAfterSave) live = "CHANGED".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static final class Fixture {
        final Path world, tokenFile, shopFile, pending, completed;
        final UUID player = UUID.randomUUID();
        final ShopTransactionJournal.Entry entry;
        final ShopBuybackSettlement.Expected expected;
        final Inventory inventory;
        Fixture(Path world) throws IOException {
            this.world = world; Files.createDirectories(world);
            tokenFile = world.resolve("data/erzmarken.json"); shopFile = world.resolve("data/erzmarken-shop.json");
            ShopTransactionJournal.initialize(world);
            ShopState shop = new ShopState(shopFile);
            shop.setOwner(UUID.randomUUID());
            shop.setRegion("minecraft:overworld", new int[]{0, 0, 0}, new int[]{10, 100, 10});
            shop.proposeBuyback("minecraft:stone", 7, "integration fixture"); shop.approveBuyback("minecraft:stone");
            TokenStore tokens = TokenStore.forWorld(world);
            check(tokens.add(player, 100));
            var quote = shop.quoteBuyback("minecraft:stone", 2);
            var journal = ShopTransactionJournal.begin(player, quote.itemId(), quote.quantity(), quote.total());
            expected = new ShopBuybackSettlement.Expected(journal.id(), player.toString(), quote.itemId(), 2, 7, 14, 114, 2486, 2);
            ShopBuybackIntentStore.prepare(world, journal, expected,
                "BEFORE_REMOVAL".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "AFTER_REMOVAL".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            shop.commitBuyback(player, quote, journal.id());
            journal = ShopTransactionJournal.advance(journal, "SHOP_DEBITED");
            tokens.creditShopPurchaseChecked(player, quote.total(), journal.id());
            journal = ShopTransactionJournal.advance(journal, "PLAYER_CREDITED");
            entry = ShopTransactionJournal.advance(journal, "ITEM_REMOVED_UNSAVED");
            pending = world.resolve("data/erzmarken-shop-journal/" + entry.id() + ".pending.json");
            completed = pending.resolveSibling(entry.id() + ".complete.json");
            inventory = new Inventory(world.resolve("player-fixture.dat"));
        }
        void finish() throws IOException { ShopBuybackSettlement.finishCurrent(world, entry, expected, inventory, inventory.snapshot()); }
        void unresolved() throws IOException {
            check(Files.exists(pending) && !Files.exists(completed));
            check(!ShopPendingTradeGate.mayJoin(world, player));
            denied(ShopTransactionJournal::ensureClear);
        }
    }
    static ShopBuybackRecoveryReview.Report review(Fixture f, boolean online) {
        return ShopBuybackRecoveryReview.inspect(f.world, f.entry.id(), online, expected -> {
            if (!Arrays.equals(expected, Files.readAllBytes(f.inventory.file))) throw new IOException("fixture inventory differs");
        });
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("buyback-settlement-");
        try {
            Fixture good = new Fixture(root.resolve("success"));
            check(!ShopPendingTradeGate.mayJoin(good.world, good.player));
            check(ShopPendingTradeGate.mayJoin(good.world, UUID.randomUUID()));
            good.finish();
            check(!Files.exists(good.pending) && Files.readString(good.completed).contains("COMPLETED_VERIFIED"));
            check(good.inventory.saves == 1);
            ShopTransactionJournal.initialize(good.world); ShopTransactionJournal.ensureClear();
            check(ShopPendingTradeGate.mayJoin(good.world, good.player));
            check(new TokenStore(good.tokenFile).balance(good.player) == 114);

            // Strict ShopState loading must preserve malformed financial files, not round them.
            Path malformed = root.resolve("invalid-shop.json");
            for (String invalid : new String[]{"{\"shopWallet\":2500.5}",
                    "{\"wholesaleStock\":{\"minecraft:stone\":2.5}}",
                    "{\"shopWallet\":2500,\"shopWallet\":2000}"}) {
                Files.writeString(malformed, invalid);
                denied(() -> new ShopState(malformed));
                check(Files.readString(malformed).equals(invalid));
            }
            check(new ShopState(good.shopFile).shopWallet() == 2486);
            check(new ShopState(good.shopFile).wholesaleStock("minecraft:stone") == 2);
            denied(good::finish); // Cannot archive twice or pay twice.
            check(good.inventory.saves == 1);
            check(new TokenStore(good.tokenFile).balance(good.player) == 114);

            Fixture saveFailure = new Fixture(root.resolve("save-failure"));
            saveFailure.inventory.failSave = true; denied(saveFailure::finish); saveFailure.unresolved();
            check(Files.readString(saveFailure.inventory.file).equals("BEFORE_REMOVAL"));
            byte[] pendingBeforeReview = Files.readAllBytes(saveFailure.pending);
            byte[] accountBeforeReview = Files.readAllBytes(saveFailure.tokenFile);
            var beforeReview = review(saveFailure, false);
            check(beforeReview.planValid() && beforeReview.financesMatch());
            check(beforeReview.inventory() == ShopBuybackRecoveryReview.InventoryMatch.BEFORE_ONLY);
            check(!beforeReview.releaseAllowed());
            check(Arrays.equals(pendingBeforeReview, Files.readAllBytes(saveFailure.pending)));
            check(Arrays.equals(accountBeforeReview, Files.readAllBytes(saveFailure.tokenFile)));
            check(!review(saveFailure, true).planValid());
            ShopTransactionJournal.initialize(saveFailure.world); saveFailure.unresolved(); // restart leaves it pending
            TokenStore retry = new TokenStore(saveFailure.tokenFile);
            retry.creditShopPurchaseChecked(saveFailure.player, 14, saveFailure.entry.id());
            check(retry.balance(saveFailure.player) == 114); // no second payment

            Fixture ambiguousSave = new Fixture(root.resolve("ambiguous-save"));
            ambiguousSave.inventory.failAfterWrite = true; denied(ambiguousSave::finish); ambiguousSave.unresolved();
            check(Files.readString(ambiguousSave.inventory.file).equals("AFTER_REMOVAL"));
            check(new TokenStore(ambiguousSave.tokenFile).balance(ambiguousSave.player) == 114);
            var afterReview = review(ambiguousSave, false);
            check(afterReview.inventory() == ShopBuybackRecoveryReview.InventoryMatch.AFTER_ONLY);
            check(afterReview.financesMatch() && !afterReview.releaseAllowed());
            check(ShopBuybackRecoveryReview.inspect(ambiguousSave.world, ambiguousSave.entry.id(), false, null).inventory()
                == ShopBuybackRecoveryReview.InventoryMatch.UNAVAILABLE);
            check(ShopBuybackRecoveryReview.inspect(ambiguousSave.world, ambiguousSave.entry.id(), false, bytes -> { }).inventory()
                == ShopBuybackRecoveryReview.InventoryMatch.BOTH);
            Files.writeString(ambiguousSave.inventory.file, "OTHER_INVENTORY");
            check(review(ambiguousSave, false).inventory() == ShopBuybackRecoveryReview.InventoryMatch.NEITHER);

            Fixture liveChange = new Fixture(root.resolve("live-change"));
            liveChange.inventory.mutateAfterSave = true; denied(liveChange::finish); liveChange.unresolved();

            String[] corruptions = {"balance", "credit", "wallet", "stock", "receipt", "duplicate", "fraction"};
            for (String corruption : corruptions) {
                Fixture f = new Fixture(root.resolve(corruption));
                JsonObject tokens = JsonParser.parseString(Files.readString(f.tokenFile)).getAsJsonObject();
                JsonObject shop = JsonParser.parseString(Files.readString(f.shopFile)).getAsJsonObject();
                switch (corruption) {
                    case "balance" -> tokens.getAsJsonObject("players").getAsJsonObject(f.player.toString()).addProperty("balance", 115);
                    case "credit" -> tokens.getAsJsonObject("shopCredits").remove(f.entry.id());
                    case "wallet" -> shop.addProperty("shopWallet", 2487);
                    case "stock" -> shop.getAsJsonObject("wholesaleStock").addProperty("minecraft:stone", 3);
                    case "receipt" -> shop.getAsJsonArray("buybackHistory").get(0).getAsJsonObject().addProperty("playerUuid", UUID.randomUUID().toString());
                    case "duplicate" -> shop.getAsJsonArray("buybackHistory").add(shop.getAsJsonArray("buybackHistory").get(0).deepCopy());
                    case "fraction" -> shop.getAsJsonArray("buybackHistory").get(0).getAsJsonObject().addProperty("quantity", 2.5);
                }
                Files.writeString(f.tokenFile, tokens.toString()); Files.writeString(f.shopFile, shop.toString());
                denied(f::finish); f.unresolved();
                var conflictReview = review(f, false);
                check(conflictReview.planValid() && !conflictReview.financesMatch() && !conflictReview.releaseAllowed());
            }
            Fixture wrongWorld = new Fixture(root.resolve("wrong-world"));
            denied(() -> ShopBuybackSettlement.finishCurrent(root.resolve("elsewhere"), wrongWorld.entry,
                wrongWorld.expected, wrongWorld.inventory, wrongWorld.inventory.snapshot()));
            check(wrongWorld.inventory.saves == 0);
            Fixture staleJournal = new Fixture(root.resolve("stale-journal"));
            JsonObject altered = JsonParser.parseString(Files.readString(staleJournal.pending)).getAsJsonObject();
            altered.addProperty("total", 15); Files.writeString(staleJournal.pending, altered.toString());
            denied(staleJournal::finish); check(staleJournal.inventory.saves == 0); staleJournal.unresolved();
            Fixture archiveCollision = new Fixture(root.resolve("archive-collision"));
            Files.createDirectory(archiveCollision.completed); denied(archiveCollision::finish);
            check(Files.exists(archiveCollision.pending)); check(!ShopPendingTradeGate.mayJoin(archiveCollision.world, archiveCollision.player));
            Files.writeString(archiveCollision.pending, "broken");
            check(!ShopPendingTradeGate.mayJoin(archiveCollision.world, UUID.randomUUID()));
            Fixture missingPlan = new Fixture(root.resolve("missing-plan"));
            Path intent = missingPlan.world.resolve("data/erzmarken-shop-intents/" + missingPlan.entry.id() + ".intent");
            Files.delete(intent);
            check(!review(missingPlan, false).planValid());
            denied(missingPlan::finish); check(missingPlan.inventory.saves == 0); missingPlan.unresolved();
            System.out.println("Buyback persistence integration: " + checks + " checks passed; inventory saver is a test double");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
