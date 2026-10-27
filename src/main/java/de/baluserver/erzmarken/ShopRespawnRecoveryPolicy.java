package de.baluserver.erzmarken;

/** Pure fail-closed rule. Normal death/respawn is unaffected for private inventory. */
public final class ShopRespawnRecoveryPolicy {
    private ShopRespawnRecoveryPolicy() { }
    public static boolean requiresQuarantine(ShopInventoryVault.Record record) {
        return record == null || record.phase() != ShopInventoryVault.Phase.PRIVATE_ACTIVE;
    }
}
