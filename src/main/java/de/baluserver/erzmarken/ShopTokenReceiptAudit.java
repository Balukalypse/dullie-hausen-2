package de.baluserver.erzmarken;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Read-only comparison of persisted token receipts and persisted journal entries.
 * A match proves receipt metadata only, never playerdata, current balance, or completion.
 */
public final class ShopTokenReceiptAudit {
    private ShopTokenReceiptAudit() { }
    private record Credit(String playerId, long amount) { }
    public record Result(List<String> lines, int matched, int missing, int conflicts,
                         int unreadable, int orphaned, boolean legacy, boolean reviewRequired) {
        public Result { lines = List.copyOf(lines); }
    }

    public static Result inspect(Path worldRoot, ShopJournalAudit.Report journalAudit, int limit) {
        if (worldRoot == null || journalAudit == null || limit < 1)
            throw new IllegalArgumentException("Ungueltige Tokenbeleg-Pruefung");
        List<String> lines = new ArrayList<>();
        Map<String, Credit> credits = new TreeMap<>();
        boolean legacy;
        try {
            JsonObject root = read(worldRoot.resolve("data/erzmarken.json"), 16 * 1024 * 1024);
            if (!root.has("players") || !root.get("players").isJsonObject())
                throw new IOException("Kontoliste fehlt");
            legacy = !root.has("shopCredits");
            if (!legacy) {
                JsonObject receipts = root.getAsJsonObject("shopCredits");
                for (var entry : receipts.entrySet()) {
                    ShopBuybackReceiptLink.requireValidTransactionId(entry.getKey());
                    JsonObject value = entry.getValue().getAsJsonObject();
                    String player = value.get("playerId").getAsString();
                    ShopBuybackReceiptLink.requireValidTransactionId(player);
                    long amount = value.get("amount").getAsBigDecimal().longValueExact();
                    if (amount <= 0 || amount > TokenStore.MAX_TOKENS) throw new IOException("Betrag ungueltig");
                    credits.put(entry.getKey(), new Credit(player, amount));
                }
            }
        } catch (IOException | RuntimeException failure) {
            return new Result(List.of("Tokenbelege: DATEI_NICHT_VERIFIZIERBAR; keine Freigabe."),
                0, 0, 0, 1, 0, false, true);
        }
        Set<String> seen = new HashSet<>();
        int matched = 0, missing = 0, conflicts = 0, unreadable = 0;
        for (var evidence : journalAudit.entries()) {
            String name = evidence.filename();
            String suffix = name.endsWith(".pending.json") ? ".pending.json"
                : name.endsWith(".complete.json") ? ".complete.json"
                : name.endsWith(".cancelled.json") ? ".cancelled.json" : null;
            if (suffix == null) continue;
            String id = name.substring(0, name.length() - suffix.length());
            try {
                ShopBuybackReceiptLink.requireValidTransactionId(id);
                if (!seen.add(id)) throw new IOException("Mehrere Journaldateien fuer dieselbe ID");
                JsonObject j = read(worldRoot.resolve("data/erzmarken-shop-journal").resolve(name), 1024 * 1024);
                var parsed = ShopTransactionJournal.decodeForReview(j, id);
                var journal = new ShopRecoveryContentMatch.Journal(parsed.id(), parsed.playerId(), parsed.itemId(),
                    parsed.quantity(), parsed.total(), parsed.stage(), parsed.createdAt());
                if (ShopRecoveryContentMatch.compare(journal, null, id)
                        != ShopRecoveryContentMatch.Decision.MISSING_RECEIPT)
                    throw new IOException("Ungueltiges Journal");
                if ((suffix.equals(".complete.json") && !"COMPLETED_VERIFIED".equals(journal.stage()))
                    || (suffix.equals(".cancelled.json") && !"CANCELLED".equals(journal.stage()))
                    || (suffix.equals(".pending.json") && ("COMPLETED_VERIFIED".equals(journal.stage())
                        || "CANCELLED".equals(journal.stage()))))
                    throw new IOException("Journalstatus passt nicht zum Dateinamen");
                Credit credit = credits.remove(id);
                if (credit == null) {
                    if ("CANCELLED".equals(journal.stage())) {
                        add(lines, limit, id + " | STORNO_OHNE_TOKENBELEG (keine Gesamtverifikation)");
                    } else {
                        missing++;
                        add(lines, limit, id + " | TOKENBELEG_FEHLT" + (legacy ? " (ALTDATEI)" : ""));
                    }
                } else if (!credit.playerId().equals(journal.playerId()) || credit.amount() != journal.total()
                        || "CANCELLED".equals(journal.stage())) {
                    conflicts++;
                    add(lines, limit, id + " | TOKENBELEG_WIDERSPRUCH");
                } else {
                    matched++;
                    add(lines, limit, id + " | TOKENBELEG_METADATEN_PASSEN (keine Transaktionsfreigabe)");
                }
            } catch (IOException | RuntimeException failure) {
                unreadable++;
                add(lines, limit, id + " | JOURNAL_NICHT_VERIFIZIERBAR");
            }
        }
        int orphaned = credits.size();
        for (String id : credits.keySet()) add(lines, limit, id + " | TOKENBELEG_OHNE_PRUEFBARES_JOURNAL");
        List<String> output = new ArrayList<>();
        output.add("Tokenbelege auf Datentraeger: passend=" + matched + ", fehlend=" + missing
            + ", Widersprueche=" + conflicts + ", unlesbar=" + unreadable + ", ohne Journal=" + orphaned
            + ", Altdatei=" + legacy);
        output.addAll(lines);
        output.add("Beleg ist KEIN Nachweis fuer aktuellen Kontostand, Warenlager oder Spielerinventar. Keine automatische Freigabe.");
        return new Result(output, matched, missing, conflicts, unreadable, orphaned, legacy,
            journalAudit.blocksTrading() || missing > 0 || conflicts > 0 || unreadable > 0 || orphaned > 0);
    }

    private static void add(List<String> lines, int limit, String line) {
        if (lines.size() < limit) lines.add(line);
    }
    private static JsonObject read(Path path, long max) throws IOException {
        return StrictJsonFiles.readObject(path, max);
    }
}
