import de.baluserver.erzmarken.*;
import static de.baluserver.erzmarken.ShopInventoryVault.Phase.*;
import static de.baluserver.erzmarken.ShopInventoryTransferPolicy.MenuAction.*;
import java.util.List;

public class ShopInventoryWorkClickPolicyTest {
    static int checks;
    static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("check " + checks); }
    record Case(String reason, ShopInventoryVault.Phase phase, boolean own, int slot, boolean empty,
                ShopInventoryTransferPolicy.MenuAction action, boolean allowed) { }
    public static void main(String[] args) {
        for (Case c : List.of(
            new Case("private cursor behavior unchanged", PRIVATE_ACTIVE, true, 9, false, OTHER, true),
            new Case("private containers unchanged", PRIVATE_ACTIVE, false, 0, true, QUICK_MOVE, true),
            new Case("private armor swap unchanged", PRIVATE_ACTIVE, true, 5, true, SWAP, true),
            new Case("new cursor stack refused", SHOP_ACTIVE, true, 9, true, OTHER, false),
            new Case("outside click refused", SHOP_ACTIVE, true, -999, true, OTHER, false),
            new Case("crafting result shift refused", SHOP_ACTIVE, true, 0, true, QUICK_MOVE, false),
            new Case("crafting input swap refused", SHOP_ACTIVE, true, 1, true, SWAP, false),
            new Case("last crafting input refused", SHOP_ACTIVE, true, 4, true, QUICK_MOVE, false),
            new Case("helmet swap overflow refused", SHOP_ACTIVE, true, 5, true, SWAP, false),
            new Case("boots swap overflow refused", SHOP_ACTIVE, true, 8, true, SWAP, false),
            new Case("shift armor removal allowed", SHOP_ACTIVE, true, 5, true, QUICK_MOVE, true),
            new Case("main to hotbar shift allowed", SHOP_ACTIVE, true, 9, true, QUICK_MOVE, true),
            new Case("hotbar swap allowed", SHOP_ACTIVE, true, 36, true, SWAP, true),
            new Case("offhand swap allowed", SHOP_ACTIVE, true, 45, true, SWAP, true),
            new Case("foreign menu shift refused", SHOP_ACTIVE, false, 9, true, QUICK_MOVE, false),
            new Case("foreign menu swap refused", SHOP_ACTIVE, false, 36, true, SWAP, false),
            new Case("nonempty cursor shift refused", SHOP_ACTIVE, true, 9, false, QUICK_MOVE, false),
            new Case("nonempty cursor swap refused", SHOP_ACTIVE, true, 36, false, SWAP, false),
            new Case("invalid slot refused", SHOP_ACTIVE, true, 46, true, SWAP, false),
            new Case("entry not acknowledged", ENTER_PREPARED, true, 9, true, QUICK_MOVE, false),
            new Case("exit not acknowledged", EXIT_PREPARED, true, 9, true, QUICK_MOVE, false),
            new Case("recovery not acknowledged", RECOVERY_REQUIRED, true, 9, true, SWAP, false),
            new Case("unreadable state refused", null, true, 9, true, SWAP, false))) {
            checks++;
            if (ShopInventoryTransferPolicy.mayClick(c.phase, c.own, c.slot, c.empty, c.action) != c.allowed)
                throw new AssertionError(c.reason);
        }
        check(!ShopInventoryTransferPolicy.mayClick(SHOP_ACTIVE, true, 9, true, null));
        System.out.println("Work click boundaries: " + checks + " checks passed; no Minecraft menu execution");
    }
}
