package de.baluserver.erzmarken;
public final class ShopPreDeathPolicyTest {
    public static void main(String[] args) {
        for (var phase : ShopInventoryVault.Phase.values()) {
            boolean expected = phase != ShopInventoryVault.Phase.PRIVATE_ACTIVE;
            check(ShopPreDeathPolicy.mustPreventDeath(true, phase, false) == expected);
            check(!ShopPreDeathPolicy.mustPreventDeath(false, phase, false));
        }
        check(ShopPreDeathPolicy.mustPreventDeath(true, null, true));
        check(!ShopPreDeathPolicy.mustPreventDeath(false, null, true));
        System.out.println("12 pre-death policy checks passed");
    }
    static void check(boolean yes) { if (!yes) throw new AssertionError("PreDeathGuard rule failed"); }
}
