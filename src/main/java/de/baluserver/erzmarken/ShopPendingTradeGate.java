package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** A pending trade must not be obscured by login rewards or subsequent gameplay. */
public final class ShopPendingTradeGate {
    private ShopPendingTradeGate() { }
    public static boolean mayJoin(Path world, UUID player) throws IOException {
        if (!ShopRetailJournal.mayJoin(world, player)) return false;
        var audit = ShopJournalAudit.inspectForAdmission(world);
        if (audit.suspicious() > 0) return false; // Cannot safely attribute corrupt/unknown evidence.
        for (var entry : audit.entries()) {
            if (!entry.filename().endsWith(".pending.json")) continue;
            try {
                String id = entry.filename().substring(0, entry.filename().length() - ".pending.json".length());
                ShopBuybackReceiptLink.requireValidTransactionId(id);
                var journal = ShopTransactionJournal.readPendingForReview(world, id);
                String owner = journal.playerId();
                if (player.toString().equals(owner)) return false;
            } catch (IOException | RuntimeException invalid) { return false; }
        }
        return true;
    }

    /** The live buyback's one exceptional credit must name the sole pending debit.
     * This does not replay a journal or verify shop/playerdata persistence.
     */
    public static void requireCredit(Path world, UUID player, String id, long amount) throws IOException {
        var audit = ShopJournalAudit.inspectForAdmission(world);
        if (audit.suspicious() != 0 || audit.pending() != 1)
            throw new IOException("Shop-Gutschrift ohne eindeutige offene Buchung verweigert");
        var journal = ShopTransactionJournal.readPendingForReview(world, id);
        if (player == null || !player.toString().equals(journal.playerId()) || amount != journal.total()
                || !"SHOP_DEBITED".equals(journal.stage()))
            throw new IOException("Shop-Gutschrift passt nicht zur offenen abgebuchten Buchung");
    }
}
