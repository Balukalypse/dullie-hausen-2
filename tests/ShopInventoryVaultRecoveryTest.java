import de.baluserver.erzmarken.ShopInventoryVault;
import java.nio.file.Files;
import java.util.UUID;

public class ShopInventoryVaultRecoveryTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("dullie-recovery-");
        var vault = new ShopInventoryVault(root);
        check(vault.inspectUnfinished().isEmpty(), "empty directory");
        UUID complete = UUID.randomUUID(), entering = UUID.randomUUID(), interrupted = UUID.randomUUID();
        vault.prepareEnter(complete, new byte[]{1}); vault.confirmEnter(complete);
        vault.prepareExit(complete, new byte[]{2}); vault.confirmExit(complete);
        vault.prepareEnter(entering, new byte[]{3});
        vault.prepareEnter(interrupted, new byte[]{4}); vault.flagRecovery(interrupted);
        var items = new ShopInventoryVault(root).inspectUnfinished();
        check(items.size() == 2, "must report both pending sessions");
        check(items.stream().anyMatch(i -> i.playerId().equals(entering) && i.phase() == ShopInventoryVault.Phase.ENTER_PREPARED), "enter pending");
        check(items.stream().anyMatch(i -> i.playerId().equals(interrupted) && i.phase() == ShopInventoryVault.Phase.RECOVERY_REQUIRED), "recovery pending");
        check(items.stream().noneMatch(i -> i.playerId().equals(complete)), "completed session excluded");
        Files.writeString(root.resolve(entering + ".vault"), "corrupt");
        items = vault.inspectUnfinished();
        check(items.stream().anyMatch(i -> i.playerId().equals(entering) && i.error() != null), "corrupt must be visible");
        Files.writeString(root.resolve("unexpected.vault"), "invalid");
        boolean failsClosed = false;
        try { vault.inspectUnfinished(); } catch (java.io.IOException ex) { failsClosed = true; }
        check(failsClosed, "unexpected filenames must fail closed");
        System.out.println("VAULT RECOVERY SCAN: 7 checks passed");
    }
}
