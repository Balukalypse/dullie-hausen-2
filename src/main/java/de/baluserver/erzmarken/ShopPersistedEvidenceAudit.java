package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only presence/integrity audit of persisted BEFORE/AFTER snapshots.
 * Checks file integrity, not the semantic correctness or atomicity of Minecraft state.
 */
public final class ShopPersistedEvidenceAudit {
    private ShopPersistedEvidenceAudit() { }

    public record Result(List<String> lines, int bothVerified, int partial,
                         int absent, int invalid) {
        public Result { lines = List.copyOf(lines); }
    }

    public static Result inspect(Path worldRoot, ShopJournalAudit.Report audit, int limit) {
        if (worldRoot == null || audit == null || limit < 1)
            throw new IllegalArgumentException("Auditparameter ungueltig");
        int both = 0, partial = 0, absent = 0, invalid = 0;
        List<String> details = new ArrayList<>();
        for (ShopJournalAudit.Evidence entry : audit.entries()) {
            String name = entry.filename();
            String suffix = name.endsWith(".pending.json") ? ".pending.json"
                : name.endsWith(".complete.json") ? ".complete.json"
                : name.endsWith(".cancelled.json") ? ".cancelled.json" : null;
            if (suffix == null) continue;
            String id = name.substring(0, name.length() - suffix.length());
            String status;
            try {
                ShopBuybackReceiptLink.requireValidTransactionId(id);
                boolean beforeExists = ShopEvidencePresence.exists(worldRoot, id, ShopTransactionEvidenceStore.Moment.BEFORE);
                boolean afterExists = ShopEvidencePresence.exists(worldRoot, id, ShopTransactionEvidenceStore.Moment.AFTER);
                boolean beforeValid = !beforeExists || ShopTransactionEvidenceStore.verify(worldRoot, id, ShopTransactionEvidenceStore.Moment.BEFORE);
                boolean afterValid = !afterExists || ShopTransactionEvidenceStore.verify(worldRoot, id, ShopTransactionEvidenceStore.Moment.AFTER);
                if (!beforeValid || !afterValid) { invalid++; status = "BELEG_BESCHAEDIGT"; }
                else if (beforeExists && afterExists) { both++; status = "VORHER_NACHHER_HASH_OK"; }
                else if (beforeExists || afterExists) { partial++; status = "NUR_EIN_SNAPSHOT"; }
                else { absent++; status = "KEINE_SNAPSHOTS"; }
            } catch (IOException | RuntimeException ex) {
                invalid++;
                status = "BELEG_NICHT_PRUEFBAR";
            }
            if (details.size() < limit) details.add(id + " | " + status);
        }
        List<String> output = new ArrayList<>();
        output.add("Persistierte Belege: vorher+nachher=" + both + ", teilweise=" + partial
            + ", keine=" + absent + ", fehlerhaft=" + invalid);
        output.addAll(details);
        output.add("SHA-256 beweist nur Snapshot-Integritaet; keine Buchungs- oder Minecraft-Spielerdaten-Bestaetigung. Keine Freigabe.");
        return new Result(output, both, partial, absent, invalid);
    }
}
