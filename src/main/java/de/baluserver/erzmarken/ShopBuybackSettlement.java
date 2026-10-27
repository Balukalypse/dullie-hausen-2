package de.baluserver.erzmarken;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Finish only the currently executing trade, after independent inventory and finance readback.
 * No startup replay, guessed rollback, or cross-file atomicity is implied.
 */
public final class ShopBuybackSettlement {
    private ShopBuybackSettlement() { }
    public record Expected(String id, String playerId, String itemId, int quantity, long unitPrice,
                           long total, long playerBalanceAfter, long walletAfter, long stockAfter) {
        public Expected {
            ShopBuybackReceiptLink.requireValidTransactionId(id);
            ShopBuybackReceiptLink.requireValidTransactionId(playerId);
            if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || quantity < 1 || quantity > 64 || unitPrice < 1 || unitPrice > 1_000_000L
                    || total != unitPrice * quantity || playerBalanceAfter < total
                    || playerBalanceAfter > TokenStore.MAX_TOKENS || walletAfter < 0
                    || walletAfter > TokenStore.MAX_TOKENS - total || stockAfter < quantity || stockAfter > 1_000_000L)
                throw new IllegalArgumentException("Ungueltiger Buchungserwartungswert");
        }
    }

    public static void finishCurrent(Path world, ShopTransactionJournal.Entry journal, Expected expected,
            ShopInventorySwitchController.InventoryAdapter inventory, byte[] after) throws IOException {
        if (journal == null || expected == null || !"ITEM_REMOVED_UNSAVED".equals(journal.stage())
                || !expected.id().equals(journal.id()) || !expected.playerId().equals(journal.playerId())
                || !expected.itemId().equals(journal.itemId()) || expected.quantity() != journal.quantity()
                || expected.total() != journal.total() || after == null || after.length == 0)
            throw new IOException("Buchung darf nicht aus diesem Zustand abgeschlossen werden");
        byte[] snapshot = after.clone();
        ShopTransactionJournal.requireWorld(world);
        ShopTransactionJournal.requirePending(journal); // Reject stale/replayed requests before any save.
        ShopBuybackIntentStore.read(world, journal.id()).requireMatches(journal, expected, snapshot);
        try {
            inventory.requirePersistenceSupport();
            inventory.persistAndVerify(snapshot.clone());
            if (!Arrays.equals(snapshot, inventory.snapshot()))
                throw new IOException("Live-Inventar hat sich waehrend Speicherung geaendert");
            verifyFinances(world, expected);
            // The journal itself rechecks immutable data and its persisted previous stage.
            ShopTransactionJournal.finish(journal);
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Ankauf nicht abschliessend verifiziert", failure); }
    }

    public static void verifyFinances(Path world, Expected expected) throws IOException {
        try {
            JsonObject tokens = StrictJsonFiles.readObject(world.resolve("data/erzmarken.json"), 16 * 1024 * 1024);
            JsonObject shop = StrictJsonFiles.readObject(world.resolve("data/erzmarken-shop.json"), 16 * 1024 * 1024);
            JsonObject account = tokens.getAsJsonObject("players").getAsJsonObject(expected.playerId());
            long balance = number(account, "balance");
            if (balance != expected.playerBalanceAfter()) throw new IOException("Kontostand: erwartet=" + expected.playerBalanceAfter() + ", gelesen=" + balance);
            JsonObject credit = tokens.getAsJsonObject("shopCredits").getAsJsonObject(expected.id());
            if (!expected.playerId().equals(credit.get("playerId").getAsString()) || number(credit, "amount") != expected.total())
                throw new IOException("Tokenbeleg fehlt oder weicht ab");
            long wallet = number(shop, "shopWallet");
            if (wallet != expected.walletAfter()) throw new IOException("Shopkasse: erwartet=" + expected.walletAfter() + ", gelesen=" + wallet);
            long stock = number(shop.getAsJsonObject("wholesaleStock"), expected.itemId());
            if (stock != expected.stockAfter()) throw new IOException("Lagerbestand: erwartet=" + expected.stockAfter() + ", gelesen=" + stock);
            int matches = 0;
            for (var value : shop.getAsJsonArray("buybackHistory")) {
                JsonObject receipt = value.getAsJsonObject();
                if (!expected.id().equals(receipt.get("id").getAsString())) continue;
                matches++;
                if (!expected.playerId().equals(receipt.get("playerUuid").getAsString())
                        || !expected.itemId().equals(receipt.get("itemId").getAsString())
                        || number(receipt, "quantity") != expected.quantity()
                        || number(receipt, "unitPrice") != expected.unitPrice()
                        || number(receipt, "paid") != expected.total()
                        || number(receipt, "walletAfter") != expected.walletAfter()
                        || number(receipt, "timestampMillis") <= 0)
                    throw new IOException("Gespeicherter Shopbeleg weicht ab");
            }
            if (matches != 1) throw new IOException("Shopbeleg fehlt oder ist doppelt");
        } catch (RuntimeException failure) { throw new IOException("Finanzdateien nicht verifizierbar", failure); }
    }
    private static long number(JsonObject object, String field) { return object.get(field).getAsBigDecimal().longValueExact(); }
}
