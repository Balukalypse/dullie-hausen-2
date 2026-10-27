import de.baluserver.erzmarken.ShopRetailPlanStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ShopRetailPlanStoreTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void denied(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("should fail"); }
        catch (IOException | IllegalArgumentException expected) { checks++; }
    }
    private interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("retail-plan-").toAbsolutePath().normalize();
        try {
            String id = UUID.randomUUID().toString();
            byte[] before = "inventory-before".getBytes(), after = "inventory-after".getBytes();
            var plan = new ShopRetailPlanStore.Plan(id, UUID.randomUUID(), "minecraft:stone", 2, 7,
                30, 2470, 10, before, after);
            check(plan.total() == 14 && plan.playerBalanceAfter() == 16);
            check(plan.walletAfter() == 2484 && plan.stockAfter() == 8);
            before[0] = 0; after[0] = 0;
            check(plan.inventoryBefore()[0] == 'i' && plan.inventoryAfter()[0] == 'i');
            plan.inventoryBefore()[0] = 0;
            check(plan.inventoryBefore()[0] == 'i');
            ShopRetailPlanStore.prepare(root, plan);
            Path file = root.resolve("data/erzmarken-retail-plans/" + id + ".plan");
            check(Files.isRegularFile(file));
            var loaded = ShopRetailPlanStore.read(root, id);
            check(loaded.id().equals(id) && loaded.player().equals(plan.player()));
            check(loaded.itemId().equals(plan.itemId()) && loaded.total() == 14);
            check(Arrays.equals(loaded.inventoryBefore(), plan.inventoryBefore()));
            check(Arrays.equals(loaded.inventoryAfter(), plan.inventoryAfter()));
            denied(() -> ShopRetailPlanStore.prepare(root, plan));
            denied(() -> ShopRetailPlanStore.read(root, UUID.randomUUID().toString()));
            byte[] changed = Files.readAllBytes(file); changed[changed.length - 1] ^= 1;
            Files.write(file, changed);
            denied(() -> ShopRetailPlanStore.read(root, id));
            denied(() -> new ShopRetailPlanStore.Plan(UUID.randomUUID().toString(), UUID.randomUUID(),
                "minecraft:stone", 2, 7, 10, 2470, 10, new byte[]{1}, new byte[]{2}));
            denied(() -> new ShopRetailPlanStore.Plan(UUID.randomUUID().toString(), UUID.randomUUID(),
                "minecraft:stone", 2, 7, 30, 2470, 1, new byte[]{1}, new byte[]{2}));
            denied(() -> new ShopRetailPlanStore.Plan(UUID.randomUUID().toString(), UUID.randomUUID(),
                "minecraft:stone", 2, 7, 30, 2470, 10, new byte[]{1}, new byte[]{1}));
            System.out.println("ShopRetailPlanStoreTest: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Unsafe cleanup");
                    Files.delete(p);
                }
            }
        }
    }
}
