package de.baluserver.erzmarken;

import java.io.IOException;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-thread only. Physical payment is limited to the single held plain ItemStack.
 *  IMPORTANT: Shop JSON, token JSON and vanilla player inventory cannot be updated
 *  atomically with the current save architecture. A hard crash between these writes
 *  may require manual reconciliation; never advertise crash-proof trading yet.
 */
public final class ShopBuybackService {
    /** Requires a successful Loom/Mixin/server audit before release. JSON cannot bypass this. */
    public static final boolean ENABLED = false;
    private ShopBuybackService() { }

    public static String sellMainHand(ServerPlayer player, int count) throws IOException {
        if (!ENABLED && !Phase2TestAccess.allowed(player.level().getServer(), player.getUUID(), Phase2TestPolicy.Feature.BUYBACK))
            throw new IllegalStateException("Ankauf wartet auf technische Freigabe");
        ShopState shop = ErzmarkenMod.shopState;
        TokenStore tokens = ErzmarkenMod.tokens;
        if (shop == null || tokens == null) throw new IllegalStateException("Shop-/Tokendaten fehlen");
        shop.requireStorageAvailable(); tokens.requireStorageAvailable();
        if (!shop.buybackTradesEnabled()) throw new IllegalStateException("Ankauf ist bis zur Freigabe gesperrt");
        if (!shop.isOpen() || !shop.contains(player))
            throw new IllegalStateException("Du musst im geoeffneten Shop stehen");
        if (player.isCreative() || player.isSpectator())
            throw new IllegalStateException("Ankauf nur im Survival-/Adventure-Modus");
        if (shop.isOwner(player)) throw new IllegalStateException("Shopbesitzer kann keine Privatitems an seinen Shop verkaufen");
        if (shop.isTemporaryCreative(player.getUUID())) throw new IllegalStateException("Alte Creative-Sitzung muss zuerst geprueft werden");
        var world = player.level().getServer().getWorldPath(LevelResource.ROOT);
        // Ownership can change while the previous owner still holds shop items.
        // Check the durable session before quotes, journaling or money/item changes.
        new ShopInventoryVault(world.resolve("data/dulliehausen-inventory-vault"))
            .requirePrivateActive(player.getUUID());
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || count < 1 || count > 64 || held.getCount() < count)
            throw new IllegalArgumentException("Zu wenig Gegenstaende in der Haupthand (maximal 64)");
        Item item = held.getItem();
        // No named/enchanted/custom-component items in the fungible shop warehouse.
        if (!ItemStack.isSameItemSameComponents(held, new ItemStack(item)))
            throw new IllegalStateException("Nur unveraenderte Items ohne Sonderdaten sind ankaufbar");
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        if (Identifier.tryParse(itemId) == null) throw new IllegalArgumentException("Ungueltiges Item");
        ShopState.BuybackQuote q = shop.quoteBuyback(itemId, count);
        long beforeBalance = tokens.balance(player.getUUID());
        if (beforeBalance > TokenStore.MAX_TOKENS - q.total())
            throw new IllegalStateException("Tokenkonto des Spielers waere zu voll");
        MinecraftShopInventoryAdapter inventory = new MinecraftShopInventoryAdapter(player);
        inventory.requirePersistenceSupport(); // No money/item changes before this succeeds.
        byte[] before = inventory.snapshot();
        byte[] plannedAfter = MinecraftBuybackSnapshot.afterRemoval(before, player.registryAccess(), itemId, count);

        var terms = new ShopBuybackExecution.Terms(player.getUUID(), itemId, count, q.unitPrice(),
            beforeBalance, q.walletBalance(), q.stockBefore());
        String id = ShopBuybackExecution.execute(world, terms, inventory, before, plannedAfter,
            new ShopBuybackExecution.Actions() {
                public void captureBefore(ShopTransactionJournal.Entry entry) throws IOException {
                    ShopMinecraftLiveEvidenceBridge.capture(world, java.util.UUID.fromString(entry.id()), player,
                        ShopOnlineInventoryEvidence.Moment.BEFORE);
                }
                public void debitShop(String id) throws IOException { shop.commitBuyback(player.getUUID(), q, id); }
                public void creditPlayer(String id) throws IOException { tokens.creditShopPurchaseChecked(player.getUUID(), q.total(), id); }
                public void removeItems() { held.shrink(count); player.getInventory().setChanged(); }
                public void captureAfter(ShopTransactionJournal.Entry entry) throws IOException {
                    ShopMinecraftLiveEvidenceBridge.capture(world, java.util.UUID.fromString(entry.id()), player,
                        ShopOnlineInventoryEvidence.Moment.AFTER);
                }
                public void interrupted(ShopTransactionJournal.Entry entry, Exception failure) {
                    ErzmarkenMod.LOGGER.error("Ankauf unterbrochen: Journal {} muss manuell geprueft werden", entry.id(), failure);
                    player.connection.disconnect(net.minecraft.network.chat.Component.literal(
                        "Dullie Hausen: Shopbuchung unterbrochen. Bitte Serververwaltung zur Pruefung kontaktieren."));
                }
            });
        return "Ankauf gespeichert und nachgeprueft: " + count + "x " + itemId + " fuer " + q.total()
            + " Token. Beleg " + id.substring(0, 8) + ".";
    }
}
