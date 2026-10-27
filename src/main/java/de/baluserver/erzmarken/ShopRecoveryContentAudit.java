package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only disk comparison. Never replays, accepts or completes a transaction. */
public final class ShopRecoveryContentAudit {
    private static final long MAX_JOURNAL_BYTES = 1024 * 1024;
    private ShopRecoveryContentAudit() { }
    public record Result(List<String> lines, int compared, int conflicts, int unreadable,
                         int missingReceipts, int duplicates) {
        public Result { lines = List.copyOf(lines); }
    }

    public static Result inspect(Path worldRoot, ShopJournalAudit.Report audit,
                                 List<ShopState.BuybackReceipt> receipts, int maxLines) {
        if (worldRoot == null || audit == null || receipts == null || maxLines < 1)
            throw new IllegalArgumentException("Auditparameter ungueltig");
        Map<String, ShopRecoveryContentMatch.Receipt> byId = new HashMap<>();
        int duplicates = 0;
        for (ShopState.BuybackReceipt receipt : receipts) {
            if (receipt == null || receipt.id() == null) { duplicates++; continue; }
            ShopRecoveryContentMatch.Receipt value = new ShopRecoveryContentMatch.Receipt(
                receipt.id(), receipt.playerUuid(), receipt.itemId(), receipt.quantity(), receipt.paid());
            if (byId.putIfAbsent(receipt.id(), value) != null) duplicates++;
        }
        List<String> lines = new ArrayList<>();
        int compared = 0, conflicts = 0, unreadable = 0, missing = 0;
        Path folder = worldRoot.resolve("data/erzmarken-shop-journal");
        for (ShopJournalAudit.Evidence evidence : audit.entries()) {
            String fileName = evidence.filename();
            String suffix = fileName.endsWith(".pending.json") ? ".pending.json"
                : fileName.endsWith(".complete.json") ? ".complete.json"
                : fileName.endsWith(".cancelled.json") ? ".cancelled.json" : null;
            if (suffix == null) continue;
            String id = fileName.substring(0, fileName.length() - suffix.length());
            try {
                ShopBuybackReceiptLink.requireValidTransactionId(id);
                Path file = folder.resolve(fileName);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(file) || Files.size(file) > MAX_JOURNAL_BYTES)
                    throw new IOException("Datei fehlt, verlinkt oder zu gross");
                var parsed = ShopTransactionJournal.decodeForReview(StrictJsonFiles.readObject(file, MAX_JOURNAL_BYTES), id);
                var journal = new ShopRecoveryContentMatch.Journal(parsed.id(), parsed.playerId(), parsed.itemId(),
                    parsed.quantity(), parsed.total(), parsed.stage(), parsed.createdAt());
                if (journal == null || !expectedStage(suffix, journal.stage()))
                    throw new IOException("Ungueltiger Dateistatus");
                var verdict = ShopRecoveryContentMatch.compare(journal, byId.get(id), id);
                switch (verdict) {
                    case MATCH -> { compared++; add(lines, maxLines, id + " | INHALT_STIMMT_UEBEREIN (nicht dauerhaft verifiziert)"); }
                    case CONFLICT -> { conflicts++; add(lines, maxLines, id + " | INHALTSKONFLIKT: Spieler/Artikel/Menge/Betrag"); }
                    case MISSING_RECEIPT -> { missing++; add(lines, maxLines, id + " | SHOPBELEG_NICHT_IN_HISTORIE (ggf. noch nicht gebucht/alte Historie)"); }
                    case INVALID_JOURNAL -> { unreadable++; add(lines, maxLines, id + " | UNGUELTIGER_JOURNALINHALT"); }
                }
            } catch (IOException | RuntimeException ex) {
                unreadable++;
                add(lines, maxLines, id + " | JOURNAL_NICHT_VERIFIZIERBAR");
            }
        }
        List<String> output = new ArrayList<>();
        output.add("Inhaltsabgleich: passend=" + compared + ", Konflikte=" + conflicts
            + ", ungueltig/unlesbar=" + unreadable + ", Beleg fehlt=" + missing
            + ", doppelte/ungueltige Shopbelege=" + duplicates);
        output.addAll(lines);
        output.add("Nur Journal gegen Shopbeleg; Inventar, Tokenkonto, Lager und Speicherbestaetigung NICHT verifiziert. Keine Freigabe.");
        return new Result(output, compared, conflicts, unreadable, missing, duplicates);
    }

    private static boolean expectedStage(String suffix, String stage) {
        return suffix.equals(".pending.json") ? ShopTransactionStages.needsManualReview(stage)
                && !"CANCELLED".equals(stage) && stage != null
            : suffix.equals(".complete.json") ? "COMPLETED_VERIFIED".equals(stage)
            : "CANCELLED".equals(stage);
    }
    private static void add(List<String> lines, int max, String line) {
        if (lines.size() < max) lines.add(line);
    }
}
