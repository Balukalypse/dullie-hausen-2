import de.baluserver.erzmarken.ShopInventoryVault;
import java.nio.file.*;
import java.util.UUID;

public class ShopInventoryVaultPathSafetyTest {
    static int checks;
    static void ok(boolean value) { if (!value) throw new AssertionError("Check " + checks); checks++; }
    static void denied(Throwing task) throws Exception {
        boolean failed = false;
        try { task.run(); } catch (java.io.IOException expected) { failed = true; }
        ok(failed);
    }
    interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("dullie-vault-hardening");
        ShopInventoryVault vault = new ShopInventoryVault(root);
        UUID id = UUID.randomUUID();
        ok(vault.read(id).phase() == ShopInventoryVault.Phase.PRIVATE_ACTIVE);
        denied(() -> vault.prepareEnter(id, new byte[1024*1024 + 1]));
        ok(!Files.exists(root.resolve(id + ".vault")));
        vault.prepareEnter(id, new byte[]{1, 2, 3});
        ok(vault.read(id).privateData().length == 3);
        Path path = root.resolve(id + ".vault");
        byte[] prior = Files.readAllBytes(path);
        Files.write(path, new byte[4*1024*1024 + 1]);
        denied(() -> vault.read(id));
        Files.write(path, prior);
        Path symlinkRoot = root.resolve("linked");
        try {
            Files.createSymbolicLink(symlinkRoot, root);
            ShopInventoryVault linked = new ShopInventoryVault(symlinkRoot);
            denied(() -> linked.read(id));
            denied(() -> linked.prepareEnter(UUID.randomUUID(), new byte[]{1}));
            Files.delete(symlinkRoot);
            UUID other = UUID.randomUUID();
            Files.createSymbolicLink(root.resolve(other + ".vault"), path);
            denied(() -> vault.read(other));
            denied(() -> vault.inspectUnfinished());
            denied(() -> vault.prepareEnter(other, new byte[]{1}));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            System.out.println("Symlink checks skipped: " + ex);
        }
        System.out.println("VAULT PATH SAFETY: " + checks + " checks passed");
    }
}
