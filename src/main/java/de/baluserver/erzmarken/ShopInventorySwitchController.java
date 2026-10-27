package de.baluserver.erzmarken;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Fail-closed orchestration of the existing durable vault.
 * The Minecraft adapter must snapshot/restore every slot, armor, offhand and
 * cursor contents consistently on the server thread while menus are closed.
 * No attempt at automatic recovery is made after a partially applied switch.
 */
public final class ShopInventorySwitchController {
    public interface InventoryAdapter {
        byte[] snapshot() throws Exception;
        void apply(byte[] completeSnapshot) throws Exception;
        void clear() throws Exception;
        /** True only when every inventory and cursor slot is empty. */
        boolean isEmpty() throws Exception;
        /** Preflight before staging or touching any slot. */
        default void requirePersistenceSupport() throws IOException {
            throw new IOException("Persistierter Inventarnachweis nicht verfuegbar");
        }
        /** Persist playerdata and independently verify it matches this snapshot.
         * Memory observations or a save request alone are insufficient.
         * Adapters without verified persistence must refuse completion.
         */
        default void persistAndVerify(byte[] expected) throws Exception {
            throw new IOException("Persistierter Inventarnachweis nicht implementiert");
        }
    }

    private final ShopInventoryVault vault;
    public ShopInventorySwitchController(ShopInventoryVault vault) { this.vault = vault; }

    public void enter(UUID player, InventoryAdapter inventory) throws IOException {
        inventory.requirePersistenceSupport();
        ShopInventoryVault.Record current = vault.read(player);
        if (current.phase() != ShopInventoryVault.Phase.PRIVATE_ACTIVE)
            throw new IOException("Shop-Inventarwechsel gesperrt: Wiederherstellung erforderlich");
        byte[] privateSnapshot = snapshot(inventory);
        // Write durable PRIVATE snapshot before changing a single live slot.
        vault.prepareEnter(player, privateSnapshot);
        try {
            if (current.shopData() == null) {
                inventory.clear();
                // The old implementation accepted a partially cleared inventory.
                // Never confirm a switch while any private item survives.
                if (!inventory.isEmpty())
                    throw new IOException("Shop-Inventar nach Leeren nicht vollstaendig leer");
                snapshot(inventory); // also detect inaccessible/corrupt live state
            } else {
                inventory.apply(current.shopData());
                verify(inventory, current.shopData(), "Shop-Inventar");
            }
            byte[] applied = snapshot(inventory);
            inventory.persistAndVerify(applied.clone());
            verify(inventory, applied, "Shop-Inventar nach Speicherung");
            vault.confirmEnter(player);
        } catch (Exception failure) {
            // A live mutation may have happened: never blindly restore or replay.
            flagRecovery(player, failure);
            throw new IOException("Shop-Einstieg unterbrochen; manuelle Pruefung erforderlich", failure);
        }
    }

    public void exit(UUID player, InventoryAdapter inventory) throws IOException {
        inventory.requirePersistenceSupport();
        ShopInventoryVault.Record current = vault.read(player);
        if (current.phase() != ShopInventoryVault.Phase.SHOP_ACTIVE)
            throw new IOException("Shop-Ausstieg nur aus aktiver Shop-Sitzung moeglich");
        byte[] shopSnapshot = snapshot(inventory);
        vault.prepareExit(player, shopSnapshot);
        try {
            inventory.apply(current.privateData());
            verify(inventory, current.privateData(), "Privat-Inventar");
            inventory.persistAndVerify(current.privateData());
            verify(inventory, current.privateData(), "Privat-Inventar nach Speicherung");
            vault.confirmExit(player);
        } catch (Exception failure) {
            flagRecovery(player, failure);
            throw new IOException("Shop-Ausstieg unterbrochen; manuelle Pruefung erforderlich", failure);
        }
    }

    /** Verify serialized inventory roundtrip before acknowledging a completed switch. */
    private static void verify(InventoryAdapter inventory, byte[] expected, String label) throws IOException {
        byte[] actual = snapshot(inventory);
        if (!Arrays.equals(expected, actual))
            throw new IOException(label + " nach Wiederherstellung unvollstaendig oder veraendert");
    }

    private static byte[] snapshot(InventoryAdapter inventory) throws IOException {
        try {
            byte[] bytes = inventory.snapshot();
            if (bytes == null || bytes.length == 0) throw new IOException("Leerer Inventar-Snapshot");
            return bytes.clone();
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Inventar konnte nicht gesichert werden", e); }
    }

    private void flagRecovery(UUID id, Exception original) {
        try { vault.flagRecovery(id); }
        catch (IOException failure) { original.addSuppressed(failure); }
    }
}
