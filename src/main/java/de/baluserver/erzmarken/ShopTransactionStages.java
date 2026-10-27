package de.baluserver.erzmarken;

/** Pure state-machine checks, independent from Minecraft and the storage format. */
public final class ShopTransactionStages {
    private ShopTransactionStages() { }
    public static boolean isKnown(String stage) {
        return "PREPARED".equals(stage) || "SHOP_DEBITED".equals(stage)
            || "PLAYER_CREDITED".equals(stage) || "ITEM_REMOVED_UNSAVED".equals(stage)
            || "COMPLETED_VERIFIED".equals(stage) || "CANCELLED".equals(stage);
    }

    public static boolean permitted(String from, String to) {
        if (from == null || to == null) return false;
        return switch (from) {
            case "PREPARED" -> to.equals("SHOP_DEBITED") || to.equals("CANCELLED");
            case "SHOP_DEBITED" -> to.equals("PLAYER_CREDITED");
            case "PLAYER_CREDITED" -> to.equals("ITEM_REMOVED_UNSAVED");
            case "ITEM_REMOVED_UNSAVED" -> to.equals("COMPLETED_VERIFIED");
            default -> false;
        };
    }

    public static boolean needsManualReview(String stage) {
        return !"COMPLETED_VERIFIED".equals(stage) && !"CANCELLED".equals(stage);
    }
}
