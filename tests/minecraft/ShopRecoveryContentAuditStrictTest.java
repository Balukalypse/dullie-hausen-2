import de.baluserver.erzmarken.*;
import java.nio.file.*;
import java.util.*;

/** Real read-only report against malformed JSON that Gson alone can coerce/truncate. */
public class ShopRecoveryContentAuditStrictTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        Path world = Files.createTempDirectory("recovery-content-strict-");
        try {
            String id = UUID.randomUUID().toString(), player = UUID.randomUUID().toString();
            Path file = Files.createDirectories(world.resolve("data/erzmarken-shop-journal")).resolve(id + ".complete.json");
            String valid = "{\"id\":\"" + id + "\",\"playerId\":\"" + player
                + "\",\"itemId\":\"minecraft:stone\",\"quantity\":2,\"total\":14,\"stage\":\"COMPLETED_VERIFIED\",\"createdAt\":1000}";
            var receipts = List.of(new ShopState.BuybackReceipt(id, 1000, player, "minecraft:stone", 2, 7, 14, 86));
            Files.writeString(file, valid);
            check(ShopRecoveryContentAudit.inspect(world, ShopJournalAudit.inspect(world), receipts, 4).compared() == 1);
            for (String corrupt : List.of(
                    valid.replace("\"quantity\":2", "\"quantity\":2.5"),
                    valid.replace("\"quantity\":2", "\"quantity\":\"2\""),
                    valid.replace("\"quantity\":2", "\"quantity\":2,\"quantity\":2"),
                    valid.replace("\"total\":14", "\"total\":15"),
                    valid.replace("\"createdAt\":1000", "\"createdAt\":0"),
                    valid.replace("COMPLETED_VERIFIED", "UNKNOWN"))) {
                Files.writeString(file, corrupt); byte[] original = Files.readAllBytes(file);
                var audit = ShopRecoveryContentAudit.inspect(world, ShopJournalAudit.inspect(world), receipts, 4);
                check(audit.unreadable() == 1 && audit.compared() == 0);
                check(Arrays.equals(original, Files.readAllBytes(file)));
            }
            System.out.println("Strict recovery content report: " + checks + " checks passed; no live server");
        } finally {
            try (var files = Files.walk(world)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
