package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Diagnostic only. Even matching current files do not automatically release an interrupted trade. */
public final class ShopBuybackRecoveryReview {
    private ShopBuybackRecoveryReview() { }
    public enum InventoryMatch { BEFORE_ONLY, AFTER_ONLY, BOTH, NEITHER, UNAVAILABLE }
    @FunctionalInterface public interface SnapshotVerifier { void verify(byte[] expected) throws IOException; }
    public record Report(String id, String stage, boolean planValid, boolean financesMatch,
                         InventoryMatch inventory, List<String> lines, boolean releaseAllowed) {
        public Report { lines = List.copyOf(lines); }
    }
    public static Report inspect(Path world, String id, boolean playerOnline, SnapshotVerifier inventory) {
        if (playerOnline) return failure(id, "ONLINE", "Spieler ist online; Bestandspruefung verweigert");
        try {
            var plan = ShopBuybackIntentStore.read(world, id);
            var current = ShopTransactionJournal.readPendingForReview(world, id);
            plan.requireMatches(current, plan.expected(), plan.after());
            if (!Set.of("PREPARED", "SHOP_DEBITED", "PLAYER_CREDITED", "ITEM_REMOVED_UNSAVED",
                    "COMPLETED_VERIFIED", "CANCELLED").contains(current.stage()))
                return failure(id, "UNBEKANNT", "Journalzustand nicht verifizierbar");
            List<String> lines = new ArrayList<>();
            boolean finances;
            String financeIssue = null;
            try { ShopBuybackSettlement.verifyFinances(world, plan.expected()); finances = true; }
            catch (IOException invalid) { finances = false; financeIssue = invalid.getMessage(); }
            InventoryMatch state = InventoryMatch.UNAVAILABLE;
            if (inventory != null) {
                boolean before = matches(inventory, plan.before()), after = matches(inventory, plan.after());
                state = before ? (after ? InventoryMatch.BOTH : InventoryMatch.BEFORE_ONLY)
                    : (after ? InventoryMatch.AFTER_ONLY : InventoryMatch.NEITHER);
            }
            lines.add("Buchung " + id + " | Journal=" + current.stage() + " | unveraenderlicher Plan vorhanden");
            lines.add("Gespeicherte Finanzen gegen Plan: " + (finances ? "PASSEND" : "ABWEICHEND/UNLESBAR"));
            if (financeIssue != null) lines.add("Finanzhinweis: " + financeIssue.substring(0, Math.min(240, financeIssue.length())).replace('\n', ' '));
            String label = switch (state) {
                case BEFORE_ONLY -> "nur urspruenglicher Zustand";
                case AFTER_ONLY -> "nur geplanter Endzustand";
                case BOTH -> "mehrdeutig, beide Zustaende passen";
                case NEITHER -> "keiner der Planstaende passt";
                case UNAVAILABLE -> "nicht pruefbar";
            };
            lines.add("Gespeichertes Inventar gegen Plan: " + label);
            lines.add("Nur aktueller Dateivergleich. Spaetere Aenderungen sind moeglich. Keine Zahlung, Reparatur oder Freigabe.");
            return new Report(id, current.stage(), true, finances, state, lines, false);
        } catch (IOException | RuntimeException invalid) {
            return failure(id, "UNBEKANNT", "Plan oder offenes Journal fehlt, ist beschaedigt oder widerspruechlich; keine Rekonstruktion geraten");
        }
    }
    private static boolean matches(SnapshotVerifier verifier, byte[] bytes) {
        try { verifier.verify(bytes); return true; }
        catch (IOException | RuntimeException invalid) { return false; }
    }
    private static Report failure(String id, String stage, String message) {
        return new Report(id, stage, false, false, InventoryMatch.UNAVAILABLE,
            List.of("Buchung " + id + " | " + message, "Manuelle Pruefung erforderlich; keine automatische Freigabe."), false);
    }
}
