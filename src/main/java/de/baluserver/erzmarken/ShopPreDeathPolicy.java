package de.baluserver.erzmarken;

/** Pure decision rule: never let vanilla drop a live shop working inventory. */
public final class ShopPreDeathPolicy {
    private ShopPreDeathPolicy() { }

    public static boolean mustPreventDeath(boolean workModeEnabled,
                                            ShopInventoryVault.Phase phase,
                                            boolean vaultUnreadable) {
        if (!workModeEnabled) return false;
        return vaultUnreadable || phase != ShopInventoryVault.Phase.PRIVATE_ACTIVE;
    }
}
