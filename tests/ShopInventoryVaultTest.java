import de.baluserver.erzmarken.ShopInventoryVault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public class ShopInventoryVaultTest {
    static void expect(boolean yes) { if (!yes) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("dullie-vault-test");
        UUID id = UUID.randomUUID();
        ShopInventoryVault vault = new ShopInventoryVault(folder);
        expect(vault.read(id).phase() == ShopInventoryVault.Phase.PRIVATE_ACTIVE);
        byte[] personal = "private+armor+offhand+xp".getBytes(StandardCharsets.UTF_8);
        byte[] stock = "shop+armor+offhand+xp".getBytes(StandardCharsets.UTF_8);
        vault.prepareEnter(id, personal);
        expect(vault.read(id).phase() == ShopInventoryVault.Phase.ENTER_PREPARED);
        // A restarted server sees the pending state, and does not assume the switch completed.
        expect(new ShopInventoryVault(folder).read(id).phase() == ShopInventoryVault.Phase.ENTER_PREPARED);
        vault.confirmEnter(id);
        vault.prepareExit(id, stock);
        expect(Arrays.equals(stock, vault.read(id).shopData()));
        vault.confirmExit(id);
        expect(vault.read(id).phase() == ShopInventoryVault.Phase.PRIVATE_ACTIVE);
        expect(Arrays.equals(stock, new ShopInventoryVault(folder).read(id).shopData()));
        vault.prepareEnter(id, personal);
        vault.flagRecovery(id);
        expect(vault.read(id).phase() == ShopInventoryVault.Phase.RECOVERY_REQUIRED);
        boolean denied = false;
        try { vault.prepareEnter(id, personal); } catch (IllegalStateException ok) { denied = true; }
        expect(denied);
        Path data = folder.resolve(id + ".vault");
        Files.writeString(data, Files.readString(data) + "CORRUPT");
        boolean corrupt = false;
        try { vault.read(id); } catch (java.io.IOException ok) { corrupt = true; }
        expect(corrupt);
        System.out.println("SHOP INVENTORY VAULT: 11 checks passed");
    }
}
