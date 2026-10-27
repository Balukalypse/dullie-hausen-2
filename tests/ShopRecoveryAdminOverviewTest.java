import de.baluserver.erzmarken.ShopJournalAudit;
import de.baluserver.erzmarken.ShopRecoveryAdminOverview;
import java.util.List;
import java.util.Set;
public final class ShopRecoveryAdminOverviewTest {
    private static int n;
    private static void yes(boolean b) { n++; if (!b) throw new AssertionError("Test " + n); }
    public static void main(String[] args) {
        var empty = ShopRecoveryAdminOverview.inspect(new ShopJournalAudit.Report(List.of(),0,0,false),Set.of());
        yes(!empty.tradingBlocked());
        yes(empty.lines().isEmpty());
        var report = new ShopJournalAudit.Report(List.of(
            new ShopJournalAudit.Evidence("a.pending.json","a",12,"UNRESOLVED_REVIEW_REQUIRED"),
            new ShopJournalAudit.Evidence("b.pending.json","b",14,"UNRESOLVED_REVIEW_REQUIRED"),
            new ShopJournalAudit.Evidence("c.complete.json","c",17,"ARCHIVED_UNVERIFIED")),2,0,true);
        var overview = ShopRecoveryAdminOverview.inspect(report, Set.of("a", "c", "d"));
        yes(overview.tradingBlocked());
        yes(overview.pendingWithReceipt()==1);
        yes(overview.pendingWithoutReceipt()==1);
        yes(overview.receiptsWithoutJournal()==1);
        yes(overview.lines().stream().anyMatch(e -> e.transactionId().equals("d") && e.journalStatus().equals("KEIN_JOURNAL_EINTRAG")));
        var lines = ShopRecoveryAdminOverview.adminLines(overview, 1);
        yes(lines.stream().anyMatch(s -> s.contains("weitere")));
        yes(lines.get(lines.size()-1).contains("KEINE automatische Freigabe"));
        yes(ShopRecoveryAdminOverview.inspect(new ShopJournalAudit.Report(List.of(),0,0,false),Set.of("orphan")).tradingBlocked());
        yes(ShopRecoveryAdminOverview.inspect(new ShopJournalAudit.Report(List.of(new ShopJournalAudit.Evidence("x.pending.json","a",10,"UNRESOLVED_REVIEW_REQUIRED")),1,0,true),Set.of()).pendingWithoutReceipt()==1);
        System.out.println(n+" ShopRecoveryAdminOverview-Pruefungen bestanden");
    }
}
