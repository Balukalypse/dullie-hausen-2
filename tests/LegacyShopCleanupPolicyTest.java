import de.baluserver.erzmarken.*;
import java.nio.file.Files;
import java.util.UUID;

public class LegacyShopCleanupPolicyTest {
    static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        var vault = new ShopInventoryVault(Files.createTempDirectory("legacy-guard-"));
        UUID id = UUID.randomUUID();
        require(LegacyShopCleanupPolicy.allowed(vault.read(id)), "absent vault safe");
        vault.prepareEnter(id, new byte[]{1});
        require(!LegacyShopCleanupPolicy.allowed(vault.read(id)), "enter prepared blocked");
        vault.confirmEnter(id);
        require(!LegacyShopCleanupPolicy.allowed(vault.read(id)), "shop active blocked");
        vault.prepareExit(id, new byte[]{2});
        require(!LegacyShopCleanupPolicy.allowed(vault.read(id)), "exit prepared blocked");
        vault.flagRecovery(id);
        require(!LegacyShopCleanupPolicy.allowed(vault.read(id)), "recovery blocked");
        require(!LegacyShopCleanupPolicy.allowed(null), "null blocked");
        System.out.println("LEGACY CLEANUP POLICY: 6 checks passed");
    }
}
