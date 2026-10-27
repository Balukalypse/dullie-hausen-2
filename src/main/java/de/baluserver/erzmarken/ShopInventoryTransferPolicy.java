package de.baluserver.erzmarken;

/** Null means unknown/unreadable. Existing sessions remain protected without feature flags. */
public final class ShopInventoryTransferPolicy {
    public enum MenuAction { QUICK_MOVE, SWAP, OTHER }
    private ShopInventoryTransferPolicy() { }
    public static boolean mayTransfer(ShopInventoryVault.Phase phase) {
        return phase == ShopInventoryVault.Phase.PRIVATE_ACTIVE;
    }
    public static boolean mayOpenMenu(ShopInventoryVault.Phase phase, boolean ownCatalogue) {
        return mayTransfer(phase) || (phase == ShopInventoryVault.Phase.SHOP_ACTIVE && ownCatalogue);
    }
    /** Work sessions start with an empty cursor/crafting grid. Keep those empty
     * rather than trying to compensate after vanilla close-menu overflow drops.
     */
    public static boolean mayClick(ShopInventoryVault.Phase phase, boolean ownInventory,
                                   int slot, boolean cursorEmpty, MenuAction action) {
        if (mayTransfer(phase)) return true;
        // Number-key swapping a large stack into a one-item armor slot can drop
        // the old equipment when inventory is full. Only shift-move armor.
        return phase == ShopInventoryVault.Phase.SHOP_ACTIVE && ownInventory && cursorEmpty
            && slot <= 45 && ((action == MenuAction.QUICK_MOVE && slot >= 5)
                || (action == MenuAction.SWAP && slot >= 9));
    }
}
