import de.baluserver.erzmarken.ShopInventoryVault.Phase;
import de.baluserver.erzmarken.ShopLifecycleController;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ShopLifecycleControllerTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Check " + checks); }
    static final class Fake implements ShopLifecycleController.Port {
        Phase phase = Phase.PRIVATE_ACTIVE;
        boolean open, reject, enterFails, exitFails, writeFails, uncheckedWriteFails, quarantined;
        final List<String> events = new ArrayList<>();
        public Phase phase() { return phase; }
        public boolean isOpen() { return open; }
        public void preflight(boolean opening, Phase current) throws IOException {
            events.add("preflight");
            if (reject) throw new IOException("refused");
        }
        public void enter() throws IOException {
            events.add("enter");
            if (enterFails) throw new IOException("inventory entry failed");
            phase = Phase.SHOP_ACTIVE;
        }
        public void exit() throws IOException {
            events.add("exit");
            if (exitFails) throw new IOException("inventory exit failed");
            phase = Phase.PRIVATE_ACTIVE;
        }
        public void setOpen(boolean value) throws IOException {
            events.add("open=" + value);
            if (uncheckedWriteFails) throw new IllegalStateException("unchecked persistence failure");
            if (writeFails) throw new IOException("status persistence failed");
            open = value;
        }
        public void quarantineOpeningFailure(IOException failure) { events.add("quarantine"); quarantined = true; }
    }
    static void fail(boolean opening, Fake p) throws Exception {
        try { ShopLifecycleController.change(opening, p); throw new AssertionError("Expected refusal"); }
        catch (IOException expected) { check(true); }
    }
    public static void main(String[] args) throws Exception {
        Fake p = new Fake();
        check(ShopLifecycleController.change(true,p));
        check(p.open && p.phase == Phase.SHOP_ACTIVE);
        check(p.events.equals(List.of("preflight","enter","open=true")));
        p.events.clear(); check(!ShopLifecycleController.change(true,p));
        check(p.events.equals(List.of("preflight"))); // Repeated command cannot re-snapshot private items.
        p.events.clear(); check(ShopLifecycleController.change(false,p));
        check(!p.open && p.phase == Phase.PRIVATE_ACTIVE);
        check(p.events.equals(List.of("preflight","open=false","exit")));
        p.events.clear(); check(!ShopLifecycleController.change(false,p));
        check(p.events.equals(List.of("preflight")));

        p = new Fake(); p.open=true; // Upgrade from old status-only opening.
        ShopLifecycleController.change(true,p);
        check(p.phase == Phase.SHOP_ACTIVE && p.events.equals(List.of("preflight","open=false","enter","open=true")));
        p = new Fake(); p.open=true; p.enterFails=true; fail(true,p);
        check(!p.open && p.events.equals(List.of("preflight","open=false","enter")));
        p = new Fake(); p.phase=Phase.SHOP_ACTIVE; // Resume close after status saved but before exit.
        ShopLifecycleController.change(false,p);
        check(p.phase == Phase.PRIVATE_ACTIVE && p.events.equals(List.of("preflight","exit")));

        p = new Fake(); p.enterFails=true; fail(true,p);
        check(!p.open && !p.events.contains("open=true"));
        p = new Fake(); p.writeFails=true; fail(true,p);
        check(!p.open && p.phase==Phase.SHOP_ACTIVE && p.quarantined);
        check(p.events.equals(List.of("preflight","enter","open=true","quarantine")));
        p = new Fake(); p.uncheckedWriteFails=true; fail(true,p);
        check(p.quarantined && !p.open);

        p = new Fake(); p.open=true; p.phase=Phase.SHOP_ACTIVE; p.writeFails=true;
        fail(false,p); check(p.open && p.phase==Phase.SHOP_ACTIVE && !p.events.contains("exit"));
        p = new Fake(); p.open=true; p.phase=Phase.SHOP_ACTIVE; p.uncheckedWriteFails=true;
        fail(false,p); check(p.open && p.phase==Phase.SHOP_ACTIVE && !p.events.contains("exit"));
        p = new Fake(); p.open=true; p.phase=Phase.SHOP_ACTIVE; p.exitFails=true;
        fail(false,p); check(!p.open && p.phase==Phase.SHOP_ACTIVE);
        check(p.events.equals(List.of("preflight","open=false","exit")));
        for (Phase invalid : List.of(Phase.ENTER_PREPARED,Phase.EXIT_PREPARED,Phase.RECOVERY_REQUIRED)) {
            for (boolean opening : List.of(true,false)) {
                p = new Fake(); p.phase=invalid; fail(opening,p);
                check(p.events.isEmpty());
            }
        }
        for (boolean opening : List.of(true,false)) {
            p = new Fake(); p.open=!opening; p.reject=true;
            fail(opening,p); check(p.events.equals(List.of("preflight")));
        }
        System.out.println("ShopLifecycleControllerTest: " + checks + " checks passed (fake ports, no Minecraft runtime)");
    }
}
