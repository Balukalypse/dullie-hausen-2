import de.baluserver.erzmarken.*;
import java.nio.file.Files;
import java.util.UUID;
public class ShopRespawnRecoveryPolicyTest {
    static void require(boolean ok, String description) { if (!ok) throw new AssertionError(description); }
    public static void main(String[] args) throws Exception {
        var vault = new ShopInventoryVault(Files.createTempDirectory("dh-death-test-"));
        var id = UUID.randomUUID();
        require(!ShopRespawnRecoveryPolicy.requiresQuarantine(vault.read(id)), "private default");
        vault.prepareEnter(id, new byte[]{1});
        require(ShopRespawnRecoveryPolicy.requiresQuarantine(vault.read(id)), "enter pending");
        vault.confirmEnter(id);
        require(ShopRespawnRecoveryPolicy.requiresQuarantine(vault.read(id)), "shop active");
        vault.prepareExit(id, new byte[]{2});
        require(ShopRespawnRecoveryPolicy.requiresQuarantine(vault.read(id)), "exit pending");
        vault.flagRecovery(id);
        require(ShopRespawnRecoveryPolicy.requiresQuarantine(vault.read(id)), "recovery");
        require(ShopRespawnRecoveryPolicy.requiresQuarantine(null), "unknown state");
        System.out.println("RESPAWN POLICY: 6 checks passed");
    }
}
