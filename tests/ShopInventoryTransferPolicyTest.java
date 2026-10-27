import de.baluserver.erzmarken.*;
import java.nio.file.*;
import java.util.*;

public class ShopInventoryTransferPolicyTest {
    static int checks;
    static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        for (var phase : ShopInventoryVault.Phase.values()) {
            boolean priv = phase == ShopInventoryVault.Phase.PRIVATE_ACTIVE;
            check(ShopInventoryTransferPolicy.mayTransfer(phase) == priv);
            check(ShopInventoryTransferPolicy.mayOpenMenu(phase, false) == priv);
            check(ShopInventoryTransferPolicy.mayOpenMenu(phase, true)
                == (priv || phase == ShopInventoryVault.Phase.SHOP_ACTIVE));
        }
        check(!ShopInventoryTransferPolicy.mayTransfer(null));
        check(!ShopInventoryTransferPolicy.mayOpenMenu(null, false));
        check(!ShopInventoryTransferPolicy.mayOpenMenu(null, true));
        Path root = Files.createTempDirectory("transfer-policy-"); UUID id = UUID.randomUUID();
        try {
            var vault = new ShopInventoryVault(root);
            check(ShopInventoryTransferPolicy.mayTransfer(vault.read(id).phase()));
            vault.prepareEnter(id, new byte[]{1});
            check(!ShopInventoryTransferPolicy.mayOpenMenu(vault.read(id).phase(), true));
            vault.confirmEnter(id);
            var restarted = new ShopInventoryVault(root);
            byte[] before = Files.readAllBytes(root.resolve(id + ".vault"));
            check(!ShopInventoryTransferPolicy.mayTransfer(restarted.read(id).phase()));
            check(ShopInventoryTransferPolicy.mayOpenMenu(restarted.read(id).phase(), true));
            check(!ShopInventoryTransferPolicy.mayOpenMenu(restarted.read(id).phase(), false));
            check(Arrays.equals(before, Files.readAllBytes(root.resolve(id + ".vault"))));
            vault.prepareExit(id, new byte[]{2});
            check(!ShopInventoryTransferPolicy.mayTransfer(vault.read(id).phase()));
            vault.confirmExit(id);
            check(ShopInventoryTransferPolicy.mayTransfer(vault.read(id).phase()));
            System.out.println("Inventory transfer policy: " + checks + " checks passed");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
