import de.baluserver.erzmarken.ShopInventoryVault;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Real durable session transitions, not a mock boolean for current ownership. */
public class ShopBuybackInventoryAdmissionTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static void denied(Path root, UUID id) throws Exception {
        var file = root.resolve(id + ".vault"); byte[] before = Files.readAllBytes(file);
        try { new ShopInventoryVault(root).requirePrivateActive(id); throw new AssertionError("accepted session"); }
        catch (IOException expected) { checks++; }
        check(Arrays.equals(before, Files.readAllBytes(file)));
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("buyback-inventory-admission-");
        try {
            UUID seller = UUID.randomUUID(); var vault = new ShopInventoryVault(root);
            vault.requirePrivateActive(seller);
            check(!Files.exists(root.resolve(seller + ".vault"))); // preflight creates no record
            byte[] privateItems = {1, 2}, shopItems = {3, 4};
            vault.prepareEnter(seller, privateItems); denied(root, seller);
            vault.confirmEnter(seller); denied(root, seller); // former owner remains blocked
            UUID other = UUID.randomUUID(); vault.requirePrivateActive(other);
            check(!Files.exists(root.resolve(other + ".vault"))); // unrelated private player unaffected
            vault.prepareExit(seller, shopItems); denied(root, seller);
            vault.confirmExit(seller);
            byte[] closed = Files.readAllBytes(root.resolve(seller + ".vault"));
            new ShopInventoryVault(root).requirePrivateActive(seller);
            check(Arrays.equals(closed, Files.readAllBytes(root.resolve(seller + ".vault"))));
            check(Arrays.equals(shopItems, vault.read(seller).shopData())); // stored shop stock is not live
            vault.prepareEnter(seller, privateItems); vault.flagRecovery(seller); denied(root, seller);
            Files.writeString(root.resolve(seller + ".vault"), "CORRUPT"); denied(root, seller);
            Path invalidRoot = root.resolve("not-a-directory"); Files.writeString(invalidRoot, "keep");
            try { new ShopInventoryVault(invalidRoot).requirePrivateActive(seller); throw new AssertionError(); }
            catch (IOException expected) { checks++; }
            check(Files.readString(invalidRoot).equals("keep"));
            System.out.println("Buyback inventory admission: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
