package de.baluserver.erzmarken;

import java.util.UUID;

/** Shared ID for a player buyback across the journal, shop receipt and evidence files. */
public final class ShopBuybackReceiptLink {
    private ShopBuybackReceiptLink() { }

    public static void requireValidTransactionId(String id) {
        if (id == null) throw new IllegalArgumentException("Transaktions-ID fehlt");
        try {
            UUID parsed = UUID.fromString(id);
            if (!parsed.toString().equals(id)) throw new IllegalArgumentException("ID nicht kanonisch");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Ungueltige oder nicht kanonische Transaktions-ID", ex);
        }
    }

    /** Use after reopening persisted records, before any human reconciliation. */
    public static boolean matches(String journalId, String receiptId,
                                  String journalPlayer, String receiptPlayer,
                                  String journalItem, String receiptItem,
                                  int journalQuantity, int receiptQuantity,
                                  long journalTotal, long receiptTotal) {
        try { requireValidTransactionId(journalId); requireValidTransactionId(receiptId); }
        catch (IllegalArgumentException ex) { return false; }
        return journalId.equals(receiptId)
                && journalPlayer != null && journalPlayer.equals(receiptPlayer)
                && journalItem != null && journalItem.equals(receiptItem)
                && journalQuantity > 0 && journalQuantity == receiptQuantity
                && journalTotal > 0 && journalTotal == receiptTotal;
    }
}
