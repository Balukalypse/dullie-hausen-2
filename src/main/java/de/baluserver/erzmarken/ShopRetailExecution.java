package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Shared retail orchestration for actual adapters and offline failure tests. Server thread only. */
public final class ShopRetailExecution {
    private ShopRetailExecution() { }
    public interface Actions {
        void debitPlayer(String id) throws IOException;
        void creditShop(String id) throws IOException;
        void deliverItems(byte[] plannedInventory) throws Exception;
        void interrupted(ShopRetailJournal.Entry entry, Exception failure);
    }

    public static String execute(Path world, ShopRetailPlanStore.Plan plan,
            ShopInventorySwitchController.InventoryAdapter inventory, Actions actions) throws IOException {
        if (world == null || plan == null || inventory == null || actions == null)
            throw new IllegalArgumentException("Verkaufsdaten fehlen");
        inventory.requirePersistenceSupport();
        try {
            if (!Arrays.equals(plan.inventoryBefore(), inventory.snapshot()))
                throw new IOException("Inventar seit Verkaufsplanung geaendert");
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Startinventar nicht pruefbar", failure); }
        ShopRetailJournal.requireClear(world);
        ShopRetailSettlement.verifyBefore(world, plan);
        ShopRetailPlanStore.prepare(world, plan);
        var journal = ShopRetailJournal.begin(world, plan);
        try {
            actions.debitPlayer(journal.id());
            journal = ShopRetailJournal.advance(world, journal, "PLAYER_DEBITED");
            actions.creditShop(journal.id());
            journal = ShopRetailJournal.advance(world, journal, "SHOP_CREDITED");
            actions.deliverItems(plan.inventoryAfter());
            if (!Arrays.equals(plan.inventoryAfter(), inventory.snapshot()))
                throw new IOException("Gelieferte Ware entspricht nicht dem geplanten Inventar");
            journal = ShopRetailJournal.advance(world, journal, "ITEM_DELIVERED_UNSAVED");
            ShopRetailSettlement.finishCurrent(world, journal, inventory);
            return journal.id();
        } catch (Exception failure) {
            try { actions.interrupted(journal, failure); }
            catch (RuntimeException callbackFailure) { failure.addSuppressed(callbackFailure); }
            if (failure instanceof IOException io) throw io;
            throw new IOException("Verkauf unterbrochen; Buchungsbelege pruefen", failure);
        }
    }
}
