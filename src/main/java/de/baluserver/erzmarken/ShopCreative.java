package de.baluserver.erzmarken;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Shop creative is deliberately temporary. Nothing from that inventory may leave as inventory. */
public final class ShopCreative {
    private ShopCreative() { }

    public static String toggle(ServerPlayer player) {
        ShopState shop = ErzmarkenMod.shopState;
        if (shop == null) return "Shopdaten noch nicht geladen.";
        if (shop.isTemporaryCreative(player.getUUID())) {
            leave(player);
            return "Kreativmodus beendet. Dein Kreativinventar wurde geloescht.";
        }
        if (!shop.isOwner(player)) return "Nur der eingetragene Shopbesitzer darf diesen Befehl benutzen.";
        // Legacy temporary creative deletes its inventory on exit. Never start new sessions
        // until persisted dual-inventory transactions are implemented and audited.
        return "§6[Dullie Hausen] §eDer alte Kreativmodus ist aus Sicherheitsgruenden gesperrt. Nutze im Shopmodus kreativ den Katalog; getrennte Inventare sind noch in Entwicklung.";
    }

    public static void leave(ServerPlayer player) {
        ShopState shop = ErzmarkenMod.shopState;
        if (shop == null || !shop.isTemporaryCreative(player.getUUID())) return;
        // A durable session always takes precedence over the destructive legacy cleanup.
        // Do not touch any item if the vault is active, damaged or unreadable.
        if (!ShopInventorySessions.mayRunLegacyCleanup(player)) {
            player.connection.disconnect(Component.literal(
                "Dullie Hausen: Inventarsitzung gesperrt. Bitte Serververwaltung kontaktieren."));
            return;
        }
        // Historical sessions only. This is deliberately NOT the new dual-inventory path.
        player.getInventory().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.setGameMode(GameType.SURVIVAL);
        try {
            shop.setTemporaryCreative(player.getUUID(), false);
        } catch (IOException ex) {
            // Fail closed: the persisted session flag makes the next login/tick wipe inventory again.
            ErzmarkenMod.LOGGER.error("Shop-Session-Flag konnte nicht gespeichert werden", ex);
        }
    }

    public static void tick(MinecraftServer server) {
        ShopState shop = ErzmarkenMod.shopState;
        if (shop == null) return;
        for (String rawId : shop.sessions()) {
            UUID id;
            try { id = UUID.fromString(rawId); } catch (IllegalArgumentException ex) { continue; }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            if (!shop.isOwner(player) || !shop.contains(player) || !player.isCreative()) {
                leave(player);
                player.sendSystemMessage(Component.literal("§6[Dullie Hausen] §eShop-Kreativmodus beendet."));
            }
        }
    }
}
