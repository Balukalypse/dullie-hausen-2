package de.baluserver.erzmarken;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Checks the 36 main inventory slots before any tokens are debited. */
public final class InventorySupport {
    private InventorySupport() { }

    public static boolean canFit(ServerPlayer player, ItemStack example, int count) {
        if (example.isEmpty() || count < 1) return false;
        int capacity = 0;
        for (int i = 0; i < Math.min(36, player.getInventory().getContainerSize()); i++) {
            ItemStack existing = player.getInventory().getItem(i);
            if (existing.isEmpty()) {
                capacity += example.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, example)) {
                capacity += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
            if (capacity >= count) return true;
        }
        return false;
    }

    public static void deliver(ServerPlayer player, ItemStack example, int count) {
        int remaining = count;
        while (remaining > 0) {
            int portion = Math.min(remaining, example.getMaxStackSize());
            ItemStack part = example.copyWithCount(portion);
            player.getInventory().add(part);
            // In normal server-thread execution this cannot fail after canFit().
            // If another mod changes the inventory unexpectedly, never destroy an item.
            if (!part.isEmpty()) player.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                    player.level(), player.getX(), player.getY(), player.getZ(), part.copy()));
            remaining -= portion;
        }
    }
}
