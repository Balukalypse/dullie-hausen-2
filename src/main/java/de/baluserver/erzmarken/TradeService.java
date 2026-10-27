package de.baluserver.erzmarken;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.io.IOException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Offers expire after two minutes. Exchange takes place only when buyer accepts. */
public final class TradeService {
    private record Offer(UUID seller, UUID buyer, ItemStack stack, int count, long price, long expiresAtMillis) { }
    private final Map<UUID, Offer> offers = new HashMap<>();

    public void clear() { offers.clear(); }
    public void cancel(UUID seller) { offers.remove(seller); }

    public String create(ServerPlayer seller, ServerPlayer buyer, long price) {
        if (seller.getUUID().equals(buyer.getUUID())) return "Du kannst nicht mit dir selbst handeln.";
        String admission = privateInventoryProblem(seller, buyer);
        if (admission != null) return admission;
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty()) return "Halte den zu verkaufenden Gegenstand in der Haupthand.";
        if (price < 1 || price > TokenStore.MAX_TOKENS) return "Ungueltiger Preis.";
        offers.put(seller.getUUID(), new Offer(seller.getUUID(), buyer.getUUID(), held.copy(), held.getCount(), price, System.currentTimeMillis() + 120_000));
        return null;
    }

    public long price(UUID seller) {
        Offer offer = offers.get(seller);
        return offer == null ? 0 : offer.price();
    }

    /** Return error text, or null for success. All checks run on the Minecraft server thread. */
    public String accept(ServerPlayer buyer, ServerPlayer seller, TokenStore ledger) {
        Offer offer = offers.get(seller.getUUID());
        if (offer == null || !buyer.getUUID().equals(offer.buyer())) return "Kein passendes Handelsangebot vorhanden.";
        // An offer may predate a shop inventory switch. Never rely on creation-time admission.
        String admission = privateInventoryProblem(seller, buyer);
        if (admission != null) {
            offers.remove(seller.getUUID());
            return admission;
        }
        if (System.currentTimeMillis() > offer.expiresAtMillis()) {
            offers.remove(seller.getUUID());
            return "Handelsangebot abgelaufen (2 Minuten).";
        }
        ItemStack held = seller.getMainHandItem();
        if (held.getCount() < offer.count() || !ItemStack.isSameItemSameComponents(held, offer.stack())) {
            offers.remove(seller.getUUID());
            return "Der Verkaeufer hat die angebotenen Gegenstaende nicht mehr in der Hand.";
        }
        if (ledger.balance(buyer.getUUID()) < offer.price()) return "Du hast nicht genug Token.";
        if (ledger.balance(seller.getUUID()) > TokenStore.MAX_TOKENS - offer.price()) return "Token-Limit des Verkaeufers erreicht.";
        if (!InventorySupport.canFit(buyer, offer.stack(), offer.count())) return "Du hast nicht genug Platz im Inventar.";
        if (!ledger.transfer(buyer.getUUID(), seller.getUUID(), offer.price())) return "Token-Uebertragung fehlgeschlagen.";
        ItemStack item = offer.stack().copyWithCount(offer.count());
        held.shrink(offer.count());
        InventorySupport.deliver(buyer, item, offer.count());
        offers.remove(seller.getUUID());
        return null;
    }

    private static String privateInventoryProblem(ServerPlayer seller, ServerPlayer buyer) {
        try {
            ShopPrivateTradeAdmission.require(seller.level().getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT), seller.getUUID(), buyer.getUUID());
            return null;
        } catch (IOException failure) {
            ErzmarkenMod.LOGGER.warn("Privathandel wegen Inventarsitzung nicht freigegeben: {} / {}",
                seller.getUUID(), buyer.getUUID(), failure);
            return "Handel gesperrt: Beide Spieler muessen ein abgeschlossenes Privat-Inventar haben. "
                + "Shop-Arbeitssitzung beenden oder pruefen lassen.";
        }
    }
}
