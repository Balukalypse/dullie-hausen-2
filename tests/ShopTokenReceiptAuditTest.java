import de.baluserver.erzmarken.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;

public final class ShopTokenReceiptAuditTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static ShopTokenReceiptAudit.Result audit(Path root) throws IOException {
        return ShopTokenReceiptAudit.inspect(root, ShopJournalAudit.inspect(root), 4);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("token-journal-audit-");
        try {
            check(audit(root).unreadable() == 1);
            ShopTransactionJournal.initialize(root);
            UUID player = UUID.randomUUID();
            var journal = ShopTransactionJournal.begin(player, "minecraft:stone", 2, 14);
            Path tokenFile = root.resolve("data/erzmarken.json");
            TokenStore tokens = new TokenStore(tokenFile);
            tokens.creditShopPurchaseChecked(player, 14, journal.id());
            byte[] originalTokens = Files.readAllBytes(tokenFile);
            Path journalFile = root.resolve("data/erzmarken-shop-journal/" + journal.id() + ".pending.json");
            byte[] originalJournal = Files.readAllBytes(journalFile);
            check(audit(root).matched() == 1);
            check(audit(root).reviewRequired()); // PREPARED still pending, despite a matching credit.
            check(Arrays.equals(originalTokens, Files.readAllBytes(tokenFile)));
            check(Arrays.equals(originalJournal, Files.readAllBytes(journalFile)));
            JsonObject token = JsonParser.parseString(new String(originalTokens, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject credit = token.getAsJsonObject("shopCredits").getAsJsonObject(journal.id());
            credit.addProperty("amount", 15);
            Files.writeString(tokenFile, token.toString()); check(audit(root).conflicts() == 1);
            credit.addProperty("amount", 14); credit.addProperty("playerId", UUID.randomUUID().toString());
            Files.writeString(tokenFile, token.toString()); check(audit(root).conflicts() == 1);
            credit.addProperty("playerId", player.toString()); credit.addProperty("amount", 14.5);
            Files.writeString(tokenFile, token.toString()); check(audit(root).unreadable() == 1);
            token.remove("shopCredits"); Files.writeString(tokenFile, token.toString());
            check(audit(root).legacy()); check(audit(root).missing() == 1);
            Files.write(tokenFile, originalTokens);
            JsonObject j = JsonParser.parseString(new String(originalJournal, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            j.addProperty("quantity", 2.5); Files.writeString(journalFile, j.toString());
            check(audit(root).unreadable() == 1);
            Files.write(journalFile, originalJournal);
            // Storno with a surviving payment is a conflict; without payment it is expected.
            j.addProperty("quantity", 2); j.addProperty("stage", "CANCELLED");
            Files.delete(journalFile);
            Path cancelled = journalFile.resolveSibling(journal.id() + ".cancelled.json");
            Files.writeString(cancelled, j.toString()); check(audit(root).conflicts() == 1);
            Files.writeString(tokenFile, token.toString()); check(!audit(root).reviewRequired());
            Files.delete(cancelled); Files.write(journalFile, originalJournal); Files.write(tokenFile, originalTokens);
            JsonObject withOrphan = JsonParser.parseString(Files.readString(tokenFile)).getAsJsonObject();
            withOrphan.getAsJsonObject("shopCredits").add(UUID.randomUUID().toString(), credit);
            credit.addProperty("amount", 14);
            Files.writeString(tokenFile, withOrphan.toString()); check(audit(root).orphaned() == 1);
            // Duplicate IDs/fields and malformed data must not be accepted by last-value-wins parsing.
            String encoded = "{\"players\":{},\"shopCredits\":{},\"shopCredits\":{}}";
            Files.writeString(tokenFile, encoded); check(audit(root).unreadable() == 1);
            Files.writeString(tokenFile, "{\"shopCredits\":{}}"); check(audit(root).unreadable() == 1);
            Files.writeString(tokenFile, "{"); check(audit(root).unreadable() == 1);
            Files.write(tokenFile, originalTokens);
            Files.write(journalFile.resolveSibling(journal.id() + ".complete.json"), originalJournal);
            check(audit(root).unreadable() >= 1); check(audit(root).reviewRequired());
            check(audit(root).lines().size() <= 6);
            // Completed metadata fixture: no playerdata proof is asserted by this test.
            Files.delete(journalFile.resolveSibling(journal.id() + ".complete.json"));
            JsonObject complete = JsonParser.parseString(new String(originalJournal, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            complete.addProperty("stage", "COMPLETED_VERIFIED");
            Files.delete(journalFile);
            Files.writeString(journalFile.resolveSibling(journal.id() + ".complete.json"), complete.toString());
            check(!audit(root).reviewRequired());
            ShopTransactionJournal.ensureClear();
            TokenStore reloaded = new TokenStore(tokenFile);
            reloaded.creditShopPurchaseChecked(player, 5, UUID.randomUUID().toString());
            check(audit(root).orphaned() == 1);
            try { ShopTransactionJournal.ensureClear(); throw new AssertionError("orphan credit must block new trades"); }
            catch (IOException expected) { checks++; }
            System.out.println("Token/journal audit: " + checks + " checks passed (real Gson and persisted files)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
