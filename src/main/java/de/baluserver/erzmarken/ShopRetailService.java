package de.baluserver.erzmarken;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.network.chat.Component;

/** Live retail entry point; activation is restricted to explicitly marked local test worlds. */
public final class ShopRetailService {
    public static final boolean ENABLED = false;
    private ShopRetailService() { }
    public static String buy(ServerPlayer player, String itemId, int quantity) throws IOException {
        if (!ENABLED && !Phase2TestAccess.allowed(player.level().getServer(), player.getUUID(), Phase2TestPolicy.Feature.RETAIL))
            throw new IllegalStateException("Warenkauf wartet auf technische Testfreigabe");
        ShopState shop = ErzmarkenMod.shopState;
        TokenStore tokens = ErzmarkenMod.tokens;
        if (shop == null || tokens == null) throw new IllegalStateException("Shop- oder Tokendaten fehlen");
        shop.requireStorageAvailable(); tokens.requireStorageAvailable();
        if (!shop.isOpen() || !shop.contains(player)) throw new IllegalStateException("Du musst im geoeffneten Shop stehen");
        if (player.isCreative() || player.isSpectator()) throw new IllegalStateException("Warenkauf nur im Survival-/Adventure-Modus");
        if (shop.isOwner(player)) throw new IllegalStateException("Shopbesitzer kann nicht bei seinem eigenen Shop einkaufen");
        if (shop.isTemporaryCreative(player.getUUID())) throw new IllegalStateException("Alte Creative-Sitzung zuerst pruefen");
        var world = player.level().getServer().getWorldPath(LevelResource.ROOT);
        new ShopInventoryVault(world.resolve("data/dulliehausen-inventory-vault")).requirePrivateActive(player.getUUID());
        ShopState.RetailQuote quote = shop.retailQuote(itemId, quantity);
        if (!quote.available() || quantity > 64) throw new IllegalStateException("Ware oder Menge aktuell nicht lieferbar (1 bis 64)");
        if (tokens.balance(player.getUUID()) < quote.totalPrice()) throw new IllegalStateException("Du hast nicht genug Token");
        shop.requirePersistedState();
        MinecraftShopInventoryAdapter inventory = new MinecraftShopInventoryAdapter(player);
        inventory.requirePersistenceSupport();
        byte[] before = inventory.snapshot();
        byte[] after;
        try { after = MinecraftRetailSnapshot.afterDelivery(before, player.registryAccess(), itemId, quantity); }
        catch (MinecraftRetailSnapshot.NoSpaceException noSpace) { throw new IllegalStateException(noSpace.getMessage(), noSpace); }
        tokens.flush(); tokens.requireStorageAvailable();
        var plan = new ShopRetailPlanStore.Plan(UUID.randomUUID().toString(), player.getUUID(), itemId, quantity,
            quote.unitPrice(), tokens.balance(player.getUUID()), shop.shopWallet(), quote.availableStock(), before, after);
        String id = ShopRetailExecution.execute(world, plan, inventory, new ShopRetailExecution.Actions() {
            public void debitPlayer(String id) throws IOException { tokens.debitShopSaleChecked(player.getUUID(), plan.total(), id); }
            public void creditShop(String id) throws IOException { shop.commitRetail(player.getUUID(), quote, id); }
            public void deliverItems(byte[] planned) throws IOException { inventory.apply(planned); }
            public void interrupted(ShopRetailJournal.Entry entry, Exception failure) {
                ErzmarkenMod.LOGGER.error("Verkauf unterbrochen: Journal {} manuell pruefen", entry.id(), failure);
                player.connection.disconnect(Component.literal("Dullie Hausen: Kauf unterbrochen. Serververwaltung muss die Buchung pruefen."));
            }
        });
        return "Kauf gespeichert und nachgeprueft: " + quantity + "x " + itemId + " fuer " + plan.total()
            + " Token. Beleg " + id.substring(0, 8) + ".";
    }
}
