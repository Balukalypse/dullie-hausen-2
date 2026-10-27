import de.baluserver.erzmarken.*;
import java.util.UUID;

public final class ShopRecoveryReviewGateTest {
    private static int checks;
    private static void expect(ShopRecoveryReviewGate.Status status, ShopRecoveryReviewGate.Review actual) {
        if (actual.status() != status || !actual.tradingBlocked())
            throw new AssertionError("Expected " + status + " got " + actual);
        checks++;
    }
    public static void main(String[] args) {
        String id = UUID.randomUUID().toString();
        var receipt = new ShopRecoveryReviewGate.Receipt(id,"player","minecraft:stone",3,6);
        var b = new ShopTradeReconciliation.Snapshot(10,100,8,9);
        var a = new ShopTradeReconciliation.Snapshot(16,94,11,6);
        var ok = new ShopTradeReconciliation.Evidence(b,a,true,true,true,true,true);
        var volatileEvidence = new ShopTradeReconciliation.Evidence(b,a,true,false,true,true,true);
        var invalidCount = new ShopTradeReconciliation.Evidence(b,
                new ShopTradeReconciliation.Snapshot(16,94,12,6),true,true,true,true,true);
        expect(ShopRecoveryReviewGate.Status.HUMAN_REVIEW_REQUIRED,
                ShopRecoveryReviewGate.assess(id,"player","minecraft:stone",3,6,"ITEMS_REMOVED",receipt,ok));
        expect(ShopRecoveryReviewGate.Status.UNVERIFIED,
                ShopRecoveryReviewGate.assess(id,"player","minecraft:stone",3,6,"ITEMS_REMOVED",receipt,volatileEvidence));
        expect(ShopRecoveryReviewGate.Status.STOCK_MISMATCH,
                ShopRecoveryReviewGate.assess(id,"player","minecraft:stone",3,6,"ITEMS_REMOVED",receipt,invalidCount));
        expect(ShopRecoveryReviewGate.Status.MISSING_RECEIPT,
                ShopRecoveryReviewGate.assess(id,"player","minecraft:stone",3,6,"ITEMS_REMOVED",null,ok));
        expect(ShopRecoveryReviewGate.Status.MISSING_EVIDENCE,
                ShopRecoveryReviewGate.assess(id,"player","minecraft:stone",3,6,"ITEMS_REMOVED",receipt,null));
        expect(ShopRecoveryReviewGate.Status.DIFFERENT_TRANSACTION,
                ShopRecoveryReviewGate.assess(id,"player","minecraft:stone",3,6,"ITEMS_REMOVED",
                    new ShopRecoveryReviewGate.Receipt(UUID.randomUUID().toString(),"player","minecraft:stone",3,6),ok));
        expect(ShopRecoveryReviewGate.Status.INVALID_RECORDS,
                ShopRecoveryReviewGate.assess("../../bad","player","minecraft:stone",3,6,"ITEMS_REMOVED",receipt,ok));
        expect(ShopRecoveryReviewGate.Status.INVALID_RECORDS,
                ShopRecoveryReviewGate.assess(id,"player","minecraft:stone",3,6,"COMPLETED_VERIFIED",receipt,ok));
        System.out.println(checks + " Recovery-Gate-Pruefungen bestanden");
    }
}
