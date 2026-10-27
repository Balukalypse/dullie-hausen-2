package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/** Shared live/offline execution path. No automatic compensation or replay. */
public final class ShopBuybackExecution {
    private ShopBuybackExecution() { }
    public record Terms(UUID player, String itemId, int quantity, long unitPrice,
                        long playerBalanceBefore, long walletBefore, long stockBefore) {
        public Terms {
            if (player == null || itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || quantity < 1 || quantity > 64 || unitPrice < 1 || unitPrice > 1_000_000L
                    || playerBalanceBefore < 0 || playerBalanceBefore > TokenStore.MAX_TOKENS - unitPrice * quantity
                    || walletBefore < unitPrice * quantity || walletBefore > TokenStore.MAX_TOKENS
                    || stockBefore < 0 || stockBefore > 1_000_000L - quantity)
                throw new IllegalArgumentException("Ungueltige Ankaufsbedingungen");
        }
        public long total() { return unitPrice * quantity; }
        ShopBuybackSettlement.Expected expected(String id) {
            return new ShopBuybackSettlement.Expected(id, player.toString(), itemId, quantity, unitPrice,
                total(), playerBalanceBefore + total(), walletBefore - total(), stockBefore + quantity);
        }
    }
    public interface Actions {
        void captureBefore(ShopTransactionJournal.Entry entry) throws IOException;
        void debitShop(String id) throws IOException;
        void creditPlayer(String id) throws IOException;
        void removeItems() throws IOException;
        void captureAfter(ShopTransactionJournal.Entry entry) throws IOException;
        void interrupted(ShopTransactionJournal.Entry entry, Exception failure);
    }

    public static String execute(Path world, Terms terms,
            ShopInventorySwitchController.InventoryAdapter inventory, byte[] before, byte[] plannedAfter,
            Actions actions) throws IOException {
        if (world == null || terms == null || inventory == null || actions == null
                || before == null || plannedAfter == null || before.length == 0 || plannedAfter.length == 0
                || before.length > 1024 * 1024 || plannedAfter.length > 1024 * 1024
                || Arrays.equals(before, plannedAfter))
            throw new IllegalArgumentException("Ungueltige Inventarplanung");
        byte[] beforeCopy = before.clone(), afterCopy = plannedAfter.clone();
        inventory.requirePersistenceSupport();
        try {
            if (!Arrays.equals(beforeCopy, inventory.snapshot()))
                throw new IOException("Inventar seit Ankaufsplanung geaendert");
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Ankaufsinventar nicht pruefbar", failure); }
        ShopTransactionJournal.requireWorld(world);
        var journal = ShopTransactionJournal.begin(terms.player(), terms.itemId(), terms.quantity(), terms.total());
        try {
            var expected = terms.expected(journal.id());
            ShopBuybackIntentStore.prepare(world, journal, expected, beforeCopy, afterCopy);
            actions.captureBefore(journal);
            actions.debitShop(journal.id());
            journal = ShopTransactionJournal.advance(journal, "SHOP_DEBITED");
            actions.creditPlayer(journal.id());
            journal = ShopTransactionJournal.advance(journal, "PLAYER_CREDITED");
            actions.removeItems();
            journal = ShopTransactionJournal.advance(journal, "ITEM_REMOVED_UNSAVED");
            actions.captureAfter(journal);
            ShopBuybackSettlement.finishCurrent(world, journal, expected, inventory, inventory.snapshot());
            return journal.id();
        } catch (Exception failure) {
            try { actions.interrupted(journal, failure); }
            catch (RuntimeException callbackFailure) { failure.addSuppressed(callbackFailure); }
            if (failure instanceof IOException io) throw io;
            throw new IOException("Ankauf unterbrochen; Buchung muss manuell geprueft werden", failure);
        }
    }
}
