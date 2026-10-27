import de.baluserver.erzmarken.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public class ShopInventoryPersistenceBarrierTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static class Adapter implements ShopInventorySwitchController.InventoryAdapter {
        byte[] live = {1}, disk = {1};
        boolean failSave, changeAfterSave;
        public byte[] snapshot() { return live.clone(); }
        public void apply(byte[] b) { live = b.clone(); }
        public void clear() { live = new byte[]{0}; }
        public boolean isEmpty() { return Arrays.equals(live, new byte[]{0}); }
        public void requirePersistenceSupport() { }
        public void persistAndVerify(byte[] expected) throws IOException {
            if (failSave) throw new IOException("simulated disk failure");
            disk = live.clone();
            check(Arrays.equals(disk, expected));
            if (changeAfterSave) live = new byte[]{99};
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("switch-persistence");
        try {
            var vault = new ShopInventoryVault(root);
            var controller = new ShopInventorySwitchController(vault);
            UUID unsupported = UUID.randomUUID();
            var noPersistence = new ShopInventorySwitchController.InventoryAdapter() {
                public byte[] snapshot() { throw new AssertionError("must not snapshot"); }
                public void apply(byte[] b) { throw new AssertionError("must not mutate"); }
                public void clear() { throw new AssertionError("must not clear"); }
                public boolean isEmpty() { throw new AssertionError("must not access inventory"); }
            };
            try { controller.enter(unsupported, noPersistence); throw new AssertionError(); }
            catch (IOException expected) { }
            check(vault.read(unsupported).phase() == ShopInventoryVault.Phase.PRIVATE_ACTIVE);
            UUID id = UUID.randomUUID(); Adapter a = new Adapter();
            controller.enter(id, a);
            check(Arrays.equals(a.disk, new byte[]{0}));
            a.failSave = true;
            try { controller.exit(id, a); throw new AssertionError(); } catch (IOException expected) { }
            // A restart still retains PRIVATE backup and blocks a second attempt.
            var recovered = new ShopInventoryVault(root).read(id);
            check(recovered.phase() == ShopInventoryVault.Phase.RECOVERY_REQUIRED);
            check(Arrays.equals(recovered.privateData(), new byte[]{1}));
            check(Arrays.equals(a.disk, new byte[]{0}));
            UUID failedEntry = UUID.randomUUID(); Adapter b = new Adapter(); b.failSave = true;
            try { controller.enter(failedEntry, b); throw new AssertionError(); } catch (IOException expected) { }
            check(vault.read(failedEntry).phase() == ShopInventoryVault.Phase.RECOVERY_REQUIRED);
            UUID changed = UUID.randomUUID(); Adapter c = new Adapter(); c.changeAfterSave = true;
            try { controller.enter(changed, c); throw new AssertionError(); } catch (IOException expected) { }
            check(vault.read(changed).phase() == ShopInventoryVault.Phase.RECOVERY_REQUIRED);
            UUID good = UUID.randomUUID(); Adapter d = new Adapter();
            controller.enter(good, d); d.live = new byte[]{42}; controller.exit(good, d);
            check(Arrays.equals(d.disk, new byte[]{1}));
            controller.enter(good, d); check(Arrays.equals(d.disk, new byte[]{42}));
            System.out.println("Persistence barrier: successful roundtrip, failed entry/exit, restart, post-save mutation passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
