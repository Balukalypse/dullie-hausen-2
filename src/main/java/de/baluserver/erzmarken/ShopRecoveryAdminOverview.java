package de.baluserver.erzmarken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only comparison of shop receipt IDs and on-disk journal audit entries.
 * Does not prove durability of vanilla playerdata or correctness of balances.
 */
public final class ShopRecoveryAdminOverview {
    public record Line(String transactionId, String journalStatus, String receiptStatus) { }
    public record Overview(List<Line> lines, int pending, int suspicious,
                           int pendingWithReceipt, int pendingWithoutReceipt,
                           int receiptsWithoutJournal, boolean tradingBlocked) {
        public Overview { lines = List.copyOf(lines); }
    }
    private ShopRecoveryAdminOverview() { }

    public static Overview inspect(ShopJournalAudit.Report audit, Set<String> receiptIds) {
        if (audit == null || receiptIds == null) throw new IllegalArgumentException("Bericht oder Belegliste fehlt");
        Set<String> remaining = new HashSet<>(receiptIds);
        List<Line> lines = new ArrayList<>();
        int with = 0, without = 0;
        for (ShopJournalAudit.Evidence entry : audit.entries()) {
            String name = entry.filename();
            String suffix = name.endsWith(".pending.json") ? ".pending.json"
                : name.endsWith(".complete.json") ? ".complete.json"
                : name.endsWith(".cancelled.json") ? ".cancelled.json" : null;
            if (suffix == null) {
                lines.add(new Line(name, entry.status(), "UNBEKANNT"));
                continue;
            }
            String id = name.substring(0, name.length() - suffix.length());
            boolean hasReceipt = remaining.remove(id);
            if (suffix.equals(".pending.json")) {
                if (hasReceipt) with++; else without++;
            }
            lines.add(new Line(id, entry.status(), hasReceipt ? "SHOPBELEG_GEFUNDEN" : "KEIN_SHOPBELEG"));
        }
        remaining.stream().sorted().forEach(id -> lines.add(new Line(id, "KEIN_JOURNAL_EINTRAG", "SHOPBELEG_GEFUNDEN")));
        // An absent receipt is NOT automatically suspicious: PREPARED can precede shop commit.
        // Old receipt history may be capped to 500 and must not be treated as complete evidence.
        return new Overview(lines, audit.pending(), audit.suspicious(), with, without,
            remaining.size(), audit.blocksTrading() || !remaining.isEmpty());
    }

    public static List<String> adminLines(Overview report, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Limit ungueltig");
        List<String> result = new ArrayList<>();
        result.add("Shop-Audit: offen=" + report.pending() + ", auffaellig=" + report.suspicious()
            + ", mit Shopbeleg=" + report.pendingWithReceipt() + ", ohne=" + report.pendingWithoutReceipt()
            + ", Beleg ohne Journal=" + report.receiptsWithoutJournal()
            + ", Ankaufsperre=" + report.tradingBlocked());
        int count = 0;
        for (Line line : report.lines()) {
            if (count++ >= limit) break;
            result.add(line.transactionId() + " | " + line.journalStatus() + " | " + line.receiptStatus());
        }
        if (report.lines().size() > limit) result.add("... weitere " + (report.lines().size() - limit) + " Eintraege");
        result.add("Nur Dateizuordnung! KEINE bestaetigte Item-/Token-Speicherung und KEINE automatische Freigabe.");
        return List.copyOf(result);
    }
}
