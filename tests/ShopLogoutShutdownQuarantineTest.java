import de.baluserver.erzmarken.ShopInventoryVault;
import java.nio.file.Files;
import java.util.UUID;
import java.util.Arrays;

/** Models durable quarantine performed by disconnect/shutdown callbacks without Minecraft runtime. */
public class ShopLogoutShutdownQuarantineTest {
    static void check(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    public static void main(String[] args) throws Exception {
        var dir = Files.createTempDirectory("dh-logout-quarantine-");
        var vault = new ShopInventoryVault(dir);
        var owner = UUID.randomUUID();
        vault.prepareEnter(owner, new byte[]{10,20,30});
        vault.confirmEnter(owner);
        vault.flagRecovery(owner); // logout/shutdown handler
        var fresh = new ShopInventoryVault(dir).read(owner);
        check(fresh.phase() == ShopInventoryVault.Phase.RECOVERY_REQUIRED, "quarantine persists across process restart");
        check(Arrays.equals(fresh.privateData(), new byte[]{10,20,30}), "private data intact");
        vault.flagRecovery(owner);
        check(vault.read(owner).phase() == ShopInventoryVault.Phase.RECOVERY_REQUIRED, "idempotent quarantine");
        var other = UUID.randomUUID();
        vault.prepareEnter(other, new byte[]{50});
        vault.flagRecovery(other);
        check(vault.inspectUnfinished().size() == 2, "shutdown audit finds both sessions");
        var normal = UUID.randomUUID();
        vault.flagRecovery(normal);
        check(vault.read(normal).phase() == ShopInventoryVault.Phase.PRIVATE_ACTIVE, "normal session not quarantined");
        var corrupt = UUID.randomUUID();
        vault.prepareEnter(corrupt, new byte[]{42});
        var file = dir.resolve(corrupt + ".vault");
        Files.writeString(file, "CORRUPT");
        boolean failed = false;
        try {vault.flagRecovery(corrupt);} catch (java.io.IOException e) {failed = true;}
        check(failed && Files.readString(file).equals("CORRUPT"), "corrupt vault remains untouched");
        System.out.println("LOGOUT-SHUTDOWN QUARANTINE: 6 checks passed");
    }
}
