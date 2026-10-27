import de.baluserver.erzmarken.ShopBuybackReceiptLink;
import java.util.UUID;

public final class ShopBuybackReceiptLinkTest {
    private static int checks;
    private static void ok(boolean value) { checks++; if (!value) throw new AssertionError("Check #" + checks); }
    private static void invalid(String value) {
        try { ShopBuybackReceiptLink.requireValidTransactionId(value); throw new AssertionError("Accepted invalid ID"); }
        catch (IllegalArgumentException correct) { checks++; }
    }
    public static void main(String[] args) {
        String id = UUID.randomUUID().toString();
        ShopBuybackReceiptLink.requireValidTransactionId(id); checks++;
        invalid(null); invalid(""); invalid("../foo"); invalid(id.toUpperCase());
        ok(ShopBuybackReceiptLink.matches(id,id,"player","player","minecraft:stone","minecraft:stone",2,2,6,6));
        ok(!ShopBuybackReceiptLink.matches(id,UUID.randomUUID().toString(),"player","player","item","item",2,2,6,6));
        ok(!ShopBuybackReceiptLink.matches(id,id,"player","other","item","item",2,2,6,6));
        ok(!ShopBuybackReceiptLink.matches(id,id,"player","player","item","other",2,2,6,6));
        ok(!ShopBuybackReceiptLink.matches(id,id,"player","player","item","item",2,3,6,6));
        ok(!ShopBuybackReceiptLink.matches(id,id,"player","player","item","item",2,2,6,7));
        ok(!ShopBuybackReceiptLink.matches(id,id,"player","player","item","item",2,2,0,0));
        System.out.println(checks + " Transaktions-ID-/Belegverknuepfungspruefungen bestanden");
    }
}
