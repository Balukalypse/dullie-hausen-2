package de.baluserver.erzmarken;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only reconciliation of a buyback transaction against independently
 * collected before/after evidence. Does not read or mutate live player state.
 * A matching comparison is NOT proof that vanilla playerdata has been flushed:
 * the caller must independently establish the provenance of the observations.
 */
public final class ShopTradeReconciliation {
    private ShopTradeReconciliation() { }

    /** Exact plain item count in the player's *entire* saved inventory, not just hand. */
    public record Snapshot(long playerTokens, long shopTokens, long shopItemCount,
                           long playerItemCount) {
        public Snapshot {
            if (playerTokens < 0 || shopTokens < 0 || shopItemCount < 0 || playerItemCount < 0)
                throw new IllegalArgumentException("Negative Bestandsangabe");
        }
    }

    public record Evidence(Snapshot before, Snapshot after,
                           boolean beforeFromDurableFiles, boolean afterFromDurableFiles,
                           boolean matchingPlayerId, boolean matchingItemId,
                           boolean matchingTransactionId) { }

    public enum Decision { MISSING_EVIDENCE, IDENTITY_MISMATCH, INCONSISTENT, MATCHES_UNVERIFIED, READY_FOR_HUMAN_REVIEW }
    public record Result(Decision decision, List<String> findings) {
        public Result { findings = List.copyOf(findings); }
        public boolean blocksTrading() { return true; } // never auto-unlock from snapshots
    }

    public static Result compare(String stage, int quantity, long payment, Evidence evidence) {
        List<String> findings = new ArrayList<>();
        if (!ShopTransactionStages.needsManualReview(stage))
            return new Result(Decision.INCONSISTENT, List.of("Kein offener Journalzustand"));
        if (quantity <= 0 || quantity > 64 || payment <= 0)
            return new Result(Decision.INCONSISTENT, List.of("Ungueltige Transaktionsparameter"));
        if (evidence == null || evidence.before() == null || evidence.after() == null)
            return new Result(Decision.MISSING_EVIDENCE, List.of("Vorher-/Nachher-Bestaende fehlen"));
        if (!evidence.matchingPlayerId() || !evidence.matchingItemId() || !evidence.matchingTransactionId())
            return new Result(Decision.IDENTITY_MISMATCH, List.of("Spieler, Item oder Journal-ID stimmen nicht ueberein"));
        Snapshot b = evidence.before(), a = evidence.after();
        // Overflow-safe differences: Math.subtractExact detects impossible huge deltas.
        try {
            checkDelta(findings, "Spielertoken", a.playerTokens(), b.playerTokens(), payment);
            checkDelta(findings, "Shopkasse", a.shopTokens(), b.shopTokens(), Math.negateExact(payment));
            checkDelta(findings, "Shoplager", a.shopItemCount(), b.shopItemCount(), quantity);
            checkDelta(findings, "Spieleritems", a.playerItemCount(), b.playerItemCount(), -((long) quantity));
        } catch (ArithmeticException overflow) {
            findings.add("Ueberlauf bei Bestandsvergleich");
        }
        if (!findings.isEmpty()) return new Result(Decision.INCONSISTENT, findings);
        if (!evidence.beforeFromDurableFiles() || !evidence.afterFromDurableFiles())
            return new Result(Decision.MATCHES_UNVERIFIED,
                List.of("Bestaende passen, aber nicht alle stammen aus unabhaengig gespeicherten Dateien"));
        return new Result(Decision.READY_FOR_HUMAN_REVIEW,
            List.of("Vier Bestandsdifferenzen stimmen; Dateiversionen, Zeitpunkte, Item-Komponenten und Journalherkunft manuell pruefen", "Keine automatische Journalfreigabe"));
    }

    private static void checkDelta(List<String> findings, String label, long after, long before, long expected) {
        long actual = Math.subtractExact(after, before);
        if (actual != expected) findings.add(label + ": Delta " + actual + ", erwartet " + expected);
    }
}
