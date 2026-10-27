import de.baluserver.erzmarken.ShopTransactionStages;
public class ShopTransactionStagesTest {
  static int count;
  static void check(boolean ok) { if (!ok) throw new AssertionError("Check " + count); count++; }
  public static void main(String[] args) {
    check(ShopTransactionStages.permitted("PREPARED", "SHOP_DEBITED"));
    check(ShopTransactionStages.permitted("SHOP_DEBITED", "PLAYER_CREDITED"));
    check(ShopTransactionStages.permitted("PLAYER_CREDITED", "ITEM_REMOVED_UNSAVED"));
    check(ShopTransactionStages.permitted("ITEM_REMOVED_UNSAVED", "COMPLETED_VERIFIED"));
    check(ShopTransactionStages.permitted("PREPARED", "CANCELLED"));
    check(!ShopTransactionStages.permitted("PLAYER_CREDITED", "COMPLETED_VERIFIED"));
    check(!ShopTransactionStages.permitted("ITEM_REMOVED_UNSAVED", "CANCELLED"));
    check(!ShopTransactionStages.permitted("COMPLETED_VERIFIED", "SHOP_DEBITED"));
    check(!ShopTransactionStages.permitted(null, "CANCELLED"));
    check(ShopTransactionStages.needsManualReview("ITEM_REMOVED_UNSAVED"));
    check(ShopTransactionStages.needsManualReview("PREPARED"));
    check(!ShopTransactionStages.needsManualReview("COMPLETED_VERIFIED"));
    check(!ShopTransactionStages.needsManualReview("CANCELLED"));
    System.out.println(count + " Journal-Zustandspruefungen bestanden");
  }
}
