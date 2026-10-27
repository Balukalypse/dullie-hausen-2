package de.baluserver.erzmarken;

/** Pure, read-only comparison of persisted buyback attributes. */
public final class ShopRecoveryContentMatch {
    private ShopRecoveryContentMatch() { }
    public record Journal(String id, String playerId, String itemId, int quantity,
                          long total, String stage, long createdAt) { }
    public record Receipt(String id, String playerId, String itemId, int quantity, long total) { }
    public enum Decision { MATCH, CONFLICT, INVALID_JOURNAL, MISSING_RECEIPT }

    public static Decision compare(Journal journal, Receipt receipt, String expectedId) {
        if (journal == null || expectedId == null || !expectedId.equals(journal.id())
                || !canonicalId(journal.id()) || !canonicalId(journal.playerId())
                || journal.itemId() == null || journal.itemId().isBlank()
                || journal.quantity() < 1 || journal.quantity() > 64 || journal.total() <= 0
                || journal.createdAt() <= 0 || !validStage(journal.stage()))
            return Decision.INVALID_JOURNAL;
        if (receipt == null) return Decision.MISSING_RECEIPT;
        return ShopBuybackReceiptLink.matches(journal.id(), receipt.id(), journal.playerId(),
                receipt.playerId(), journal.itemId(), receipt.itemId(), journal.quantity(),
                receipt.quantity(), journal.total(), receipt.total()) ? Decision.MATCH : Decision.CONFLICT;
    }

    private static boolean canonicalId(String id) {
        try { ShopBuybackReceiptLink.requireValidTransactionId(id); return true; }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static boolean validStage(String stage) {
        return "PREPARED".equals(stage) || "SHOP_DEBITED".equals(stage)
            || "PLAYER_CREDITED".equals(stage) || "ITEM_REMOVED_UNSAVED".equals(stage)
            || "COMPLETED_VERIFIED".equals(stage) || "CANCELLED".equals(stage);
    }
}
