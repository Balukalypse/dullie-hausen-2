import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ShopPrivateTradeAdmissionTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static void denied(Path world, UUID... participants) throws Exception {
        Map<Path, byte[]> before = new HashMap<>();
        try (var files = Files.walk(world)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) before.put(p, Files.readAllBytes(p));
        }
        try { ShopPrivateTradeAdmission.require(world, participants); throw new AssertionError("admitted"); }
        catch (IOException expected) { checks++; }
        try (var files = Files.walk(world)) {
            var after = files.filter(Files::isRegularFile).toList();
            check(after.size() == before.size());
            for (Path p : after) check(Arrays.equals(before.get(p), Files.readAllBytes(p)));
        }
    }
    public static void main(String[] args) throws Exception {
        Path world = Files.createTempDirectory("private-trade-admission-");
        try {
            Path root = world.resolve("data/dulliehausen-inventory-vault");
            var vault = new ShopInventoryVault(root); UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();
            ShopPrivateTradeAdmission.require(world, seller, buyer);
            check(!Files.exists(root));
            // Admission must be checked again when accepting an older offer.
            for (UUID active : List.of(seller, buyer)) {
                vault.prepareEnter(active, new byte[]{1}); denied(world, seller, buyer);
                vault.confirmEnter(active); denied(world, seller, buyer);
                vault.prepareExit(active, new byte[]{2}); denied(world, seller, buyer);
                vault.confirmExit(active); ShopPrivateTradeAdmission.require(world, seller, buyer); checks++;
                ShopPrivateTradeAdmission.require(world, active); checks++; // ordinary purchase
            }
            vault.prepareEnter(buyer, new byte[]{3}); vault.flagRecovery(buyer);
            denied(world, seller, buyer); denied(world, buyer); denied(world, buyer, seller);
            Files.writeString(root.resolve(buyer + ".vault"), "CORRUPT");
            denied(world, seller, buyer); denied(world, buyer);
            denied(world); denied(world, (UUID[])null); denied(world, seller, null);
            denied(world, seller, buyer, UUID.randomUUID());
            System.out.println("Private trade admission: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(world)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
