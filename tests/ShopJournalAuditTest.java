import de.baluserver.erzmarken.ShopJournalAudit;
import java.nio.file.*;
public final class ShopJournalAuditTest {
    private static int checks;
    private static void check(boolean good) { if (!good) throw new AssertionError("Check " + checks); checks++; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("shop-audit-test-");
        var empty = ShopJournalAudit.inspect(root);
        check(!empty.blocksTrading() && empty.pending()==0);
        Path folder = Files.createDirectories(root.resolve("data/erzmarken-shop-journal"));
        Path pending = folder.resolve("abc.pending.json");
        Files.writeString(pending, "{\"stage\":\"PLAYER_CREDITED\"}");
        var first = ShopJournalAudit.inspect(root);
        check(first.pending() == 1);
        check(first.blocksTrading());
        check(first.entries().get(0).sha256().length() == 64);
        Path report = root.resolve("audit/report.txt");
        ShopJournalAudit.exportReport(root, report);
        check(Files.readString(report).contains("KEINE automatische Freigabe"));
        boolean refused = false;
        try { ShopJournalAudit.exportReport(root, report); } catch (java.io.IOException expected) { refused = true; }
        check(refused);
        check(Files.exists(pending));
        Files.writeString(folder.resolve("abc.complete.json"), "{}");
        check(ShopJournalAudit.inspect(root).suspicious() > 0);
        Files.delete(pending);
        Files.delete(folder.resolve("abc.complete.json"));
        Files.writeString(folder.resolve("unexpected.tmp"), "temporary");
        var unexpected = ShopJournalAudit.inspect(root);
        check(unexpected.blocksTrading() && unexpected.suspicious() > 0);
        System.out.println(checks + " Shop-Journal-Audit-Pruefungen bestanden");
    }
}
