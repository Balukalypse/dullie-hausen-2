package de.baluserver.erzmarken;

import java.util.List;

/** Read-only gate combining journal/receipt identity and stock reconciliation.
 * Never commits, replays, removes items or releases a pending journal transaction.
 */
public final class ShopRecoveryReviewGate {
    private ShopRecoveryReviewGate() { }

    public enum Status { INVALID_RECORDS, MISSING_RECEIPT, MISSING_EVIDENCE,
        DIFFERENT_TRANSACTION, STOCK_MISMATCH, UNVERIFIED, HUMAN_REVIEW_REQUIRED }

    public record Review(Status status, List<String> issues, boolean tradingBlocked) {
        public Review { issues = List.copyOf(issues); }
    }

    public record Receipt(String transactionId, String playerId, String itemId,
                          int quantity, long payment) { }

    public static Review assess(String journalId, String playerId, String itemId,
                                int quantity, long payment, String journalStage,
                                Receipt receipt, ShopTradeReconciliation.Evidence evidence) {
        if (!ShopTransactionStages.needsManualReview(journalStage)
                || !validId(journalId) || playerId == null || playerId.isBlank()
                || itemId == null || itemId.isBlank() || quantity <= 0 || quantity > 64 || payment <= 0)
            return result(Status.INVALID_RECORDS, "Ungueltiger oder nicht offener Journaleintrag");
        if (receipt == null) return result(Status.MISSING_RECEIPT, "Shopbeleg fehlt");
        if (!ShopBuybackReceiptLink.matches(journalId, receipt.transactionId(), playerId,
                receipt.playerId(), itemId, receipt.itemId(), quantity, receipt.quantity(),
                payment, receipt.payment()))
            return result(Status.DIFFERENT_TRANSACTION, "Shopbeleg und Journal widersprechen sich");
        if (evidence == null || evidence.before() == null || evidence.after() == null)
            return result(Status.MISSING_EVIDENCE, "Bestandsaufnahmen fehlen");
        ShopTradeReconciliation.Result reconciled = ShopTradeReconciliation.compare(
                journalStage, quantity, payment, evidence);
        return switch (reconciled.decision()) {
            case MISSING_EVIDENCE -> new Review(Status.MISSING_EVIDENCE, reconciled.findings(), true);
            case IDENTITY_MISMATCH -> new Review(Status.DIFFERENT_TRANSACTION, reconciled.findings(), true);
            case INCONSISTENT -> new Review(Status.STOCK_MISMATCH, reconciled.findings(), true);
            case MATCHES_UNVERIFIED -> new Review(Status.UNVERIFIED, reconciled.findings(), true);
            case READY_FOR_HUMAN_REVIEW -> new Review(Status.HUMAN_REVIEW_REQUIRED, reconciled.findings(), true);
        };
    }

    private static boolean validId(String id) {
        try { ShopBuybackReceiptLink.requireValidTransactionId(id); return true; }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static Review result(Status status, String reason) {
        return new Review(status, List.of(reason), true);
    }
}
