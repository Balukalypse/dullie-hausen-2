package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft-facing entry point. Must be called on the server thread after menus close.
 * This only captures volatile server memory. It cannot certify playerdata persistence.
 */
public final class ShopMinecraftLiveEvidenceBridge {
    private ShopMinecraftLiveEvidenceBridge() { }
    public static ShopOnlineInventoryEvidence.Receipt capture(Path world, UUID transaction,
            ServerPlayer player, ShopOnlineInventoryEvidence.Moment moment) throws IOException {
        if (player == null || player.level().isClientSide()) throw new IOException("Server player required");
        if (!player.level().getServer().isSameThread()) throw new IOException("Live snapshot must run on server thread");
        MinecraftShopInventoryAdapter adapter = new MinecraftShopInventoryAdapter(player);
        return ShopOnlineInventoryEvidence.capture(world, transaction, player.getUUID(), moment, adapter::snapshot);
    }
}
