import de.baluserver.erzmarken.ShopJournalTransitionGuard;
public class ShopJournalTransitionGuardTest {
    private static int checks;
    static void check(boolean ok) { if (!ok) throw new AssertionError("Test " + checks); checks++; }
    public static void main(String[] args) {
        String id="b805126a-50ab-4c52-8657-ab96bde83720";
        check(ShopJournalTransitionGuard.mayAdvance(id,"PREPARED",id,"PREPARED","SHOP_DEBITED"));
        check(!ShopJournalTransitionGuard.mayAdvance(id,"PREPARED",id,"SHOP_DEBITED","SHOP_DEBITED"));
        check(!ShopJournalTransitionGuard.mayAdvance(id,"PREPARED","other","PREPARED","SHOP_DEBITED"));
        check(!ShopJournalTransitionGuard.mayAdvance(id,"PREPARED",id,"PREPARED","PLAYER_CREDITED"));
        check(!ShopJournalTransitionGuard.mayAdvance(id,"PREPARED",id,"PREPARED","PREPARED"));
        check(!ShopJournalTransitionGuard.mayAdvance(null,"PREPARED",id,"PREPARED","SHOP_DEBITED"));
        check(!ShopJournalTransitionGuard.mayAdvance(id,null,id,"PREPARED","SHOP_DEBITED"));
        check(ShopJournalTransitionGuard.mayAdvance(id,"SHOP_DEBITED",id,"SHOP_DEBITED","PLAYER_CREDITED"));
        check(!ShopJournalTransitionGuard.mayAdvance(id,"PLAYER_CREDITED",id,"SHOP_DEBITED","ITEM_REMOVED_UNSAVED"));
        check(ShopJournalTransitionGuard.mayAdvance(id,"PLAYER_CREDITED",id,"PLAYER_CREDITED","ITEM_REMOVED_UNSAVED"));
        System.out.println(checks+" Journal-Konfliktpruefungen bestanden");
    }
}
