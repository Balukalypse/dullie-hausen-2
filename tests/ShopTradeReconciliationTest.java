import de.baluserver.erzmarken.ShopTradeReconciliation;
import de.baluserver.erzmarken.ShopTransactionStages;

public final class ShopTradeReconciliationTest {
    private static int n;
    static void eq(Object actual, Object expected) {
        if (!actual.equals(expected)) throw new AssertionError("Test " + n + ": " + actual + " != " + expected);
        n++;
    }
    static void truth(boolean yes) { eq(yes, true); }
    static ShopTradeReconciliation.Evidence evidence(ShopTradeReconciliation.Snapshot a, boolean durable) {
        return new ShopTradeReconciliation.Evidence(new ShopTradeReconciliation.Snapshot(20, 100, 10, 30), a, true, durable, true, true, true);
    }
    public static void main(String[] args) {
        var good = new ShopTradeReconciliation.Snapshot(25, 95, 12, 28);
        var ok = ShopTradeReconciliation.compare("ITEM_REMOVED_UNSAVED", 2, 5, evidence(good, true));
        eq(ok.decision(), ShopTradeReconciliation.Decision.READY_FOR_HUMAN_REVIEW);
        truth(ok.blocksTrading());
        eq(ShopTradeReconciliation.compare("ITEM_REMOVED_UNSAVED", 2, 5, evidence(good, false)).decision(), ShopTradeReconciliation.Decision.MATCHES_UNVERIFIED);
        eq(ShopTradeReconciliation.compare("PREPARED", 2, 5, null).decision(), ShopTradeReconciliation.Decision.MISSING_EVIDENCE);
        eq(ShopTradeReconciliation.compare("PREPARED", 0, 5, evidence(good, true)).decision(), ShopTradeReconciliation.Decision.INCONSISTENT);
        eq(ShopTradeReconciliation.compare("PREPARED", 2, 0, evidence(good, true)).decision(), ShopTradeReconciliation.Decision.INCONSISTENT);
        eq(ShopTradeReconciliation.compare("COMPLETED_VERIFIED", 2, 5, evidence(good, true)).decision(), ShopTradeReconciliation.Decision.INCONSISTENT);
        eq(ShopTradeReconciliation.compare("ITEM_REMOVED_UNSAVED", 2, 5, new ShopTradeReconciliation.Evidence(evidence(good, true).before(), good, true, true, false, true, true)).decision(), ShopTradeReconciliation.Decision.IDENTITY_MISMATCH);
        eq(ShopTradeReconciliation.compare("ITEM_REMOVED_UNSAVED", 2, 5, evidence(new ShopTradeReconciliation.Snapshot(25, 95, 12, 29), true)).decision(), ShopTradeReconciliation.Decision.INCONSISTENT);
        eq(ShopTradeReconciliation.compare("ITEM_REMOVED_UNSAVED", 2, 5, evidence(new ShopTradeReconciliation.Snapshot(25, 95, 11, 28), true)).decision(), ShopTradeReconciliation.Decision.INCONSISTENT);
        eq(ShopTradeReconciliation.compare("ITEM_REMOVED_UNSAVED", 2, 5, evidence(new ShopTradeReconciliation.Snapshot(25, 96, 12, 28), true)).decision(), ShopTradeReconciliation.Decision.INCONSISTENT);
        eq(ShopTradeReconciliation.compare("ITEM_REMOVED_UNSAVED", 2, 5, evidence(new ShopTradeReconciliation.Snapshot(24, 95, 12, 28), true)).decision(), ShopTradeReconciliation.Decision.INCONSISTENT);
        var huge = new ShopTradeReconciliation.Snapshot(Long.MAX_VALUE, 95, 12, 28);
        eq(ShopTradeReconciliation.compare("ITEM_REMOVED_UNSAVED", 2, 5, evidence(huge, true)).decision(), ShopTradeReconciliation.Decision.INCONSISTENT);
        try { new ShopTradeReconciliation.Snapshot(-1, 0, 0, 0); throw new AssertionError("Negative allowed"); }
        catch (IllegalArgumentException expected) { n++; }
        System.out.println(n + " Shop-Transaktions-Abgleich-Pruefungen bestanden");
    }
}
