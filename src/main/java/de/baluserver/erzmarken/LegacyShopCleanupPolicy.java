package de.baluserver.erzmarken;

/** Isolated, testable policy for the deprecated destructive creative cleanup. */
public final class LegacyShopCleanupPolicy {
    private LegacyShopCleanupPolicy() { }
    public static boolean allowed(ShopInventoryVault.Record record) {
        return record != null && record.phase() == ShopInventoryVault.Phase.PRIVATE_ACTIVE;
    }
}
