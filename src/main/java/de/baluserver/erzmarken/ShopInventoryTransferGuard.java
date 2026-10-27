package de.baluserver.erzmarken;

import java.io.IOException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.storage.LevelResource;

/** Read-only server bridge. Never restore, remove, compensate or write items in a veto. */
public final class ShopInventoryTransferGuard {
    private ShopInventoryTransferGuard() { }
    private static ShopInventoryVault.Phase phase(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null || !server.isSameThread()) return null;
        try {
            return new ShopInventoryVault(server.getWorldPath(LevelResource.ROOT)
                .resolve("data/dulliehausen-inventory-vault")).read(player.getUUID()).phase();
        } catch (IOException | RuntimeException failure) {
            // Login/death/recovery checks report corruption. Do not flood logs per item tick.
            return null;
        }
    }
    public static boolean mayTransfer(ServerPlayer player) {
        return ShopInventoryTransferPolicy.mayTransfer(phase(player));
    }
    public static boolean mayOpenMenu(ServerPlayer player, MenuProvider provider) {
        return ShopInventoryTransferPolicy.mayOpenMenu(phase(player),
            ShopCatalogueMenu.isOwnProvider(provider, player));
    }
    public static boolean mayUseCatalogue(ServerPlayer player) {
        return ShopInventoryTransferPolicy.mayOpenMenu(phase(player), true);
    }
    public static boolean mayClick(ServerPlayer player, AbstractContainerMenu menu, int slot, ContainerInput input) {
        var action = input == ContainerInput.QUICK_MOVE ? ShopInventoryTransferPolicy.MenuAction.QUICK_MOVE
            : input == ContainerInput.SWAP ? ShopInventoryTransferPolicy.MenuAction.SWAP
            : ShopInventoryTransferPolicy.MenuAction.OTHER;
        return ShopInventoryTransferPolicy.mayClick(phase(player), menu == player.inventoryMenu && menu.slots.size() == 46,
            slot, menu.getCarried().isEmpty(), action);
    }
}
