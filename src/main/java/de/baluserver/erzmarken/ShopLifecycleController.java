package de.baluserver.erzmarken;

import java.io.IOException;

/** Coordinates the two commands without treating inventory and shop files as atomic.
 * Opening publishes availability last; closing removes availability first.
 * Interrupted inventory transactions continue to use the vault's recovery gate.
 */
public final class ShopLifecycleController {
    private ShopLifecycleController() { }
    public interface Port {
        ShopInventoryVault.Phase phase() throws IOException;
        boolean isOpen();
        void preflight(boolean opening, ShopInventoryVault.Phase phase) throws IOException;
        void enter() throws IOException;
        void exit() throws IOException;
        void setOpen(boolean value) throws IOException;
        void quarantineOpeningFailure(IOException failure);
    }

    /** @return true only if availability or inventory mode needed changing. */
    public static boolean change(boolean opening, Port port) throws IOException {
        var phase = port.phase();
        if (phase != ShopInventoryVault.Phase.PRIVATE_ACTIVE && phase != ShopInventoryVault.Phase.SHOP_ACTIVE)
            throw new IOException("Shop-Wechsel gesperrt: Inventar-Wiederherstellung erforderlich");
        port.preflight(opening, phase);
        boolean changed = port.isOpen() != opening
            || phase != (opening ? ShopInventoryVault.Phase.SHOP_ACTIVE : ShopInventoryVault.Phase.PRIVATE_ACTIVE);
        if (opening) {
            boolean entered = phase == ShopInventoryVault.Phase.PRIVATE_ACTIVE;
            // Upgrade/mismatch: an old status-only open must not stay tradable while entering.
            if (entered && port.isOpen()) publish(port, false);
            if (entered) port.enter();
            try {
                if (!port.isOpen()) port.setOpen(true);
            } catch (IOException | RuntimeException problem) {
                IOException failure = problem instanceof IOException io ? io
                    : new IOException("Shopstatus konnte nicht gespeichert werden", problem);
                if (entered) {
                    try { port.quarantineOpeningFailure(failure); }
                    catch (RuntimeException quarantineFailure) { failure.addSuppressed(quarantineFailure); }
                }
                throw failure;
            }
        } else {
            // If restoring private items fails, trading must already be closed.
            if (port.isOpen()) publish(port, false);
            if (phase == ShopInventoryVault.Phase.SHOP_ACTIVE) port.exit();
        }
        return changed;
    }

    private static void publish(Port port, boolean open) throws IOException {
        try { port.setOpen(open); }
        catch (RuntimeException failure) { throw new IOException("Shopstatus konnte nicht gespeichert werden", failure); }
    }
}
